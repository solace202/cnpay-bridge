package dev.cnpay.order;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cnpay.config.CnpayProperties;
import dev.cnpay.config.PublicUrls;
import dev.cnpay.money.Money;
import dev.cnpay.notify.EventResult;
import dev.cnpay.notify.PaymentEventRepository;
import dev.cnpay.provider.Channel;
import dev.cnpay.provider.PayInstruction;
import dev.cnpay.provider.ProviderException;
import dev.cnpay.provider.ProviderRegistry;
import dev.cnpay.provider.TradeSnapshot;
import dev.cnpay.web.ApiException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orders;
    private final PaymentEventRepository events;
    private final ProviderRegistry providers;
    private final CnpayProperties properties;
    private final PublicUrls urls;
    private final ObjectMapper mapper;
    private final Clock clock;

    public OrderService(OrderRepository orders, PaymentEventRepository events, ProviderRegistry providers,
                        CnpayProperties properties, PublicUrls urls, ObjectMapper mapper, Clock clock) {
        this.orders = orders;
        this.events = events;
        this.providers = providers;
        this.properties = properties;
        this.urls = urls;
        this.mapper = mapper;
        this.clock = clock;
    }

    public OrderView create(CreateOrderRequest req, String callerIp) {
        long fen = Money.parseYuan(req.amount());
        Channel channel = req.channel();
        if (channel == Channel.WECHAT_JSAPI && (req.payerOpenid() == null || req.payerOpenid().isBlank())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "payerOpenid is required for WECHAT_JSAPI");
        }
        String clientIp = req.clientIp() != null && !req.clientIp().isBlank() ? req.clientIp() : callerIp;
        orders.findByMerchantOrderNo(req.merchantOrderNo()).ifPresent(existing -> {
            throw duplicate(existing);
        });

        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MILLIS);
        Duration ttl = req.expiresInMinutes() != null ? Duration.ofMinutes(req.expiresInMinutes()) : properties.orderTtl();
        Order order = new Order(UUID.randomUUID().toString(), req.merchantOrderNo(), Ids.outTradeNo(now),
                channel.provider(), channel, req.description(), fen, 0, "CNY", OrderStatus.PENDING,
                req.payerOpenid(), clientIp, null, null, null, now.plus(ttl).truncatedTo(ChronoUnit.SECONDS),
                null, null, now, now);
        try {
            orders.insert(order);
        } catch (DuplicateKeyException e) {
            throw duplicate(orders.findByMerchantOrderNo(req.merchantOrderNo()).orElseThrow());
        }

        PayInstruction instruction;
        try {
            instruction = providers.get(channel.provider()).create(order);
        } catch (ProviderException e) {
            orders.markClosed(order.id(), LocalDateTime.now(clock));
            throw e;
        }
        Map<String, Object> payment = new LinkedHashMap<>();
        payment.put("kind", instruction.kind());
        payment.putAll(instruction.data());
        orders.savePayload(order.id(), json(payment));
        log.info("Order {} ({}) created for {} {}", order.outTradeNo(), channel, Money.display(fen), order.merchantOrderNo());
        return view(orders.findById(order.id()).orElseThrow());
    }

    public Order get(String id) {
        return orders.findById(id).orElseThrow(() -> ApiException.notFound("Order " + id));
    }

    public OrderView view(Order order) {
        return OrderView.of(order, payment(order), urls.checkoutUrl(order.id()));
    }

    public List<Order> recent(int limit) {
        return orders.recent(limit);
    }

    public Map<String, Object> payment(Order order) {
        if (order.payPayload() == null) {
            return Map.of();
        }
        try {
            return mapper.readValue(order.payPayload(), new TypeReference<LinkedHashMap<String, Object>>() { });
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }

    /**
     * Active query: asks the provider for the trade state. Used by the poller, by the "sync" endpoint and as a
     * fallback when a notification never arrives. Expired, unpaid orders are closed on both sides.
     */
    public Order sync(Order order) {
        if (order.status() != OrderStatus.PENDING) {
            return order;
        }
        TradeSnapshot snapshot = providers.get(order.provider()).query(order.outTradeNo());
        LocalDateTime now = LocalDateTime.now(clock);
        if (snapshot.state() == TradeSnapshot.TradeState.PAID || snapshot.state() == TradeSnapshot.TradeState.REFUNDED) {
            if (snapshot.amountFen() != order.amountFen()) {
                events.recordDelivery(order.provider(), "query-" + order.outTradeNo(), "QUERY.AMOUNT_MISMATCH",
                        order.outTradeNo(), true, EventResult.REJECTED,
                        "Provider reports " + Money.display(snapshot.amountFen()) + " but order is "
                                + Money.display(order.amountFen()), null);
                return order;
            }
            boolean applied = orders.markPaid(order.outTradeNo(), snapshot.providerTradeNo(), snapshot.payer(),
                    snapshot.paidAt() != null ? snapshot.paidAt() : now);
            events.recordDelivery(order.provider(), "query-" + order.outTradeNo(), "QUERY.TRADE_SUCCESS",
                    order.outTradeNo(), true, applied ? EventResult.APPLIED : EventResult.DUPLICATE,
                    applied ? "No notification received; order query says paid → PENDING → PAID"
                            : "Already PAID", null);
        } else if (snapshot.state() == TradeSnapshot.TradeState.CLOSED) {
            orders.markClosed(order.id(), now);
        } else if (order.expiresAt().isBefore(now)) {
            closeOnProvider(order);
            if (orders.markClosed(order.id(), now)) {
                events.recordDelivery(order.provider(), "expired-" + order.outTradeNo(), "ORDER.EXPIRED",
                        order.outTradeNo(), true, EventResult.APPLIED,
                        "Unpaid at " + order.expiresAt() + "; closed at provider and locally", null);
            }
        }
        return orders.findById(order.id()).orElseThrow();
    }

    /** Merchant-initiated close (customer abandoned the cart). Closing first at the provider prevents late payment. */
    public Order close(Order order) {
        if (order.status() != OrderStatus.PENDING) {
            throw new ApiException(HttpStatus.CONFLICT, "ORDER_NOT_PENDING", "Only PENDING orders can be closed");
        }
        Order synced = sync(order);
        if (synced.status() != OrderStatus.PENDING) {
            return synced; // it was paid in the meantime
        }
        closeOnProvider(order);
        orders.markClosed(order.id(), LocalDateTime.now(clock));
        return orders.findById(order.id()).orElseThrow();
    }

    private void closeOnProvider(Order order) {
        try {
            providers.get(order.provider()).close(order.outTradeNo());
        } catch (ProviderException e) {
            log.warn("Closing {} at {} failed: {}", order.outTradeNo(), order.provider(), e.getMessage());
        }
    }

    private ApiException duplicate(Order existing) {
        return new ApiException(HttpStatus.CONFLICT, "ORDER_EXISTS",
                "merchantOrderNo " + existing.merchantOrderNo() + " already has payment order " + existing.id());
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
