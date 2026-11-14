package dev.cnpay.web;

import dev.cnpay.idempotency.IdempotencyService;
import dev.cnpay.order.CreateOrderRequest;
import dev.cnpay.order.Order;
import dev.cnpay.order.OrderService;
import dev.cnpay.order.OrderView;
import dev.cnpay.refund.CreateRefundRequest;
import dev.cnpay.refund.RefundRepository;
import dev.cnpay.refund.RefundService;
import dev.cnpay.refund.RefundView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Orders", description = "Create and track WeChat Pay / Alipay payment orders")
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orders;
    private final RefundService refundService;
    private final RefundRepository refunds;
    private final IdempotencyService idempotency;

    public OrderController(OrderService orders, RefundService refundService, RefundRepository refunds,
                           IdempotencyService idempotency) {
        this.orders = orders;
        this.refundService = refundService;
        this.refunds = refunds;
        this.idempotency = idempotency;
    }

    @Operation(summary = "Create a payment order",
            description = "Returns what the client needs to pay: a QR payload (WECHAT_NATIVE, ALIPAY_QR), "
                    + "wx.requestPayment parameters (WECHAT_JSAPI) or a redirect URL (WECHAT_H5, ALIPAY_PAGE, ALIPAY_WAP).")
    @PostMapping
    public ResponseEntity<?> create(
            @Parameter(description = "Retries with the same key and body return the original response")
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateOrderRequest request, HttpServletRequest http) {
        return idempotency.run("orders", idempotencyKey, request,
                () -> ResponseEntity.status(HttpStatus.CREATED).body(orders.create(request, http.getRemoteAddr())));
    }

    @Operation(summary = "List recent orders")
    @GetMapping
    public List<OrderView> list(@RequestParam(defaultValue = "50") int limit) {
        return orders.recent(Math.min(Math.max(limit, 1), 200)).stream().map(orders::view).toList();
    }

    @Operation(summary = "Get an order")
    @GetMapping("/{id}")
    public OrderView get(@PathVariable String id) {
        return orders.view(orders.get(id));
    }

    @Operation(summary = "Query the provider now",
            description = "Active query for a PENDING order (the poller does this automatically); closes it if expired.")
    @PostMapping("/{id}/sync")
    public OrderView sync(@PathVariable String id) {
        return orders.view(orders.sync(orders.get(id)));
    }

    @Operation(summary = "Close an unpaid order", description = "Closes at the provider first so a late scan cannot succeed.")
    @PostMapping("/{id}/close")
    public OrderView close(@PathVariable String id) {
        return orders.view(orders.close(orders.get(id)));
    }

    @Tag(name = "Refunds")
    @Operation(summary = "Refund all or part of a paid order",
            description = "Alipay refunds settle synchronously; WeChat Pay returns PROCESSING and settles via REFUND.SUCCESS.")
    @PostMapping("/{id}/refunds")
    public ResponseEntity<?> refund(@PathVariable String id,
                                    @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                    @Valid @RequestBody CreateRefundRequest request) {
        record Scoped(String orderId, CreateRefundRequest request) { }
        return idempotency.run("refunds", idempotencyKey, new Scoped(id, request),
                () -> ResponseEntity.status(HttpStatus.CREATED).body(RefundView.of(refundService.create(id, request))));
    }

    @Tag(name = "Refunds")
    @Operation(summary = "List an order's refunds")
    @GetMapping("/{id}/refunds")
    public List<RefundView> refunds(@PathVariable String id) {
        Order order = orders.get(id);
        return refunds.forOrder(order.id()).stream().map(RefundView::of).toList();
    }
}
