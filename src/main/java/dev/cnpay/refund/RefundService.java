package dev.cnpay.refund;

import dev.cnpay.money.Money;
import dev.cnpay.order.Ids;
import dev.cnpay.order.Order;
import dev.cnpay.order.OrderRepository;
import dev.cnpay.provider.ProviderException;
import dev.cnpay.provider.ProviderRegistry;
import dev.cnpay.provider.RefundOutcome;
import dev.cnpay.web.ApiException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Refunds are reserved under a row lock on the order (so two concurrent partial refunds cannot exceed the
 * paid amount), sent to the provider outside the transaction, and settled either synchronously (Alipay)
 * or by the REFUND.SUCCESS callback (WeChat Pay).
 */
@Service
public class RefundService {

    private static final Logger log = LoggerFactory.getLogger(RefundService.class);

    private final RefundRepository refunds;
    private final OrderRepository orders;
    private final ProviderRegistry providers;
    private final TransactionTemplate tx;
    private final Clock clock;

    public RefundService(RefundRepository refunds, OrderRepository orders, ProviderRegistry providers,
                         TransactionTemplate tx, Clock clock) {
        this.refunds = refunds;
        this.orders = orders;
        this.providers = providers;
        this.tx = tx;
        this.clock = clock;
    }

    public Refund create(String orderId, CreateRefundRequest request) {
        long fen = Money.parseYuan(request.amount());
        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MILLIS);
        record Reserved(Order order, Refund refund) { }
        Reserved reserved = tx.execute(status -> {
            Order order = orders.lockById(orderId).orElseThrow(() -> ApiException.notFound("Order " + orderId));
            if (!order.status().paid()) {
                throw new ApiException(HttpStatus.CONFLICT, "ORDER_NOT_PAID",
                        "Order is " + order.status() + "; only paid orders can be refunded");
            }
            long available = order.refundableFen() - refunds.processingFen(orderId);
            if (fen > available) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "REFUND_EXCEEDS_BALANCE",
                        "Requested " + Money.display(fen) + " but only " + Money.display(available) + " is refundable");
            }
            Refund refund = new Refund(UUID.randomUUID().toString(), orderId, Ids.outRefundNo(now), fen,
                    request.reason() == null || request.reason().isBlank() ? null : request.reason(),
                    RefundStatus.PROCESSING, null, now, null);
            refunds.insert(refund);
            return new Reserved(order, refund);
        });

        RefundOutcome outcome;
        try {
            outcome = providers.get(reserved.order().provider()).refund(reserved.order(), reserved.refund());
        } catch (ProviderException e) {
            refunds.markFailed(reserved.refund().outRefundNo());
            throw e;
        }
        if (outcome.status() == RefundOutcome.Status.SUCCESS) {
            applySucceeded(reserved.refund().outRefundNo(), outcome.providerRefundNo(), LocalDateTime.now(clock));
        } else {
            refunds.setProviderRefundNo(reserved.refund().outRefundNo(), outcome.providerRefundNo());
        }
        log.info("Refund {} of {} for {}: {}", reserved.refund().outRefundNo(), Money.display(fen),
                reserved.order().outTradeNo(), outcome.status());
        return refunds.findById(reserved.refund().id()).orElseThrow();
    }

    /** PROCESSING → SUCCESS exactly once; adds to the order's refunded total in the same transaction. */
    public boolean applySucceeded(String outRefundNo, String providerRefundNo, LocalDateTime at) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            Refund refund = refunds.findByOutRefundNo(outRefundNo).orElse(null);
            if (refund == null || !refunds.markSucceeded(outRefundNo, providerRefundNo, at)) {
                return false;
            }
            orders.addRefunded(refund.orderId(), refund.amountFen());
            return true;
        }));
    }

}
