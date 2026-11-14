package dev.cnpay.web;

import dev.cnpay.config.CnpayProperties;
import dev.cnpay.money.Money;
import dev.cnpay.notify.PaymentEventRepository;
import dev.cnpay.order.Order;
import dev.cnpay.order.OrderService;
import dev.cnpay.order.OrderStatus;
import dev.cnpay.provider.Provider;
import dev.cnpay.provider.ProviderException;
import dev.cnpay.recon.Reconciliation;
import dev.cnpay.recon.ReconciliationService;
import dev.cnpay.refund.CreateRefundRequest;
import dev.cnpay.refund.RefundRepository;
import dev.cnpay.refund.RefundService;
import dev.cnpay.sandbox.SandboxNotifier;
import dev.cnpay.sandbox.SandboxService;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Operator pages: orders, the notification/event log, refunds and reconciliation reports. */
@Controller
@RequestMapping("/admin")
public class AdminController {

    private final OrderService orders;
    private final RefundService refundService;
    private final RefundRepository refunds;
    private final PaymentEventRepository events;
    private final ReconciliationService reconciliation;
    private final ObjectProvider<SandboxService> sandbox;
    private final ObjectProvider<SandboxNotifier> notifier;
    private final CnpayProperties properties;
    private final Clock clock;

    public AdminController(OrderService orders, RefundService refundService, RefundRepository refunds,
                           PaymentEventRepository events, ReconciliationService reconciliation,
                           ObjectProvider<SandboxService> sandbox, ObjectProvider<SandboxNotifier> notifier,
                           CnpayProperties properties, Clock clock) {
        this.orders = orders;
        this.refundService = refundService;
        this.refunds = refunds;
        this.events = events;
        this.reconciliation = reconciliation;
        this.sandbox = sandbox;
        this.notifier = notifier;
        this.properties = properties;
        this.clock = clock;
    }

    @GetMapping
    public String dashboard(Model model) {
        List<Order> recent = orders.recent(50);
        model.addAttribute("orders", recent);
        model.addAttribute("paidCount", recent.stream().filter(o -> o.status().paid()).count());
        model.addAttribute("pendingCount", recent.stream().filter(o -> o.status() == OrderStatus.PENDING).count());
        model.addAttribute("grossFen", recent.stream().filter(o -> o.status().paid()).mapToLong(Order::amountFen).sum());
        model.addAttribute("refundedFen", recent.stream().mapToLong(Order::refundedFen).sum());
        common(model);
        return "admin";
    }

    @GetMapping("/orders/{id}")
    public String order(@PathVariable String id, Model model) {
        Order order = orders.get(id);
        model.addAttribute("order", order);
        model.addAttribute("refunds", refunds.forOrder(id));
        model.addAttribute("events", events.forTrade(order.outTradeNo()));
        model.addAttribute("refundable", Money.toYuan(order.refundableFen() - refunds.processingFen(id)));
        common(model);
        return "order";
    }

    @PostMapping("/orders/{id}/refunds")
    public String refund(@PathVariable String id, @RequestParam String amount,
                         @RequestParam(required = false) String reason, RedirectAttributes flash) {
        try {
            var refund = refundService.create(id, new CreateRefundRequest(amount, reason));
            flash.addFlashAttribute("ok", "Refund " + refund.outRefundNo() + " of ¥" + Money.toYuan(refund.amountFen())
                    + " is " + refund.status());
        } catch (ApiException | IllegalArgumentException | ProviderException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/orders/" + id;
    }

    @PostMapping("/orders/{id}/sync")
    public String sync(@PathVariable String id, RedirectAttributes flash) {
        try {
            Order after = orders.sync(orders.get(id));
            flash.addFlashAttribute("ok", "Queried provider: order is " + after.status());
        } catch (ProviderException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/orders/" + id;
    }

    @GetMapping("/events")
    public String events(Model model) {
        model.addAttribute("events", events.recent(100));
        common(model);
        return "events";
    }

    @GetMapping("/reconciliations")
    public String reconciliations(Model model) {
        model.addAttribute("runs", reconciliation.recent(30));
        model.addAttribute("today", LocalDate.now(clock));
        model.addAttribute("muted", notifier.getIfAvailable() == null ? 0 : notifier.getIfAvailable().mutedCount());
        common(model);
        return "reconciliations";
    }

    @PostMapping("/reconciliations")
    public String runReconciliation(@RequestParam Provider provider, @RequestParam LocalDate date, RedirectAttributes flash) {
        try {
            Reconciliation r = reconciliation.run(provider, date);
            return "redirect:/admin/reconciliations/" + r.id();
        } catch (ProviderException | IllegalArgumentException e) {
            flash.addFlashAttribute("error", e.getMessage());
            return "redirect:/admin/reconciliations";
        }
    }

    @GetMapping("/reconciliations/{id}")
    public String report(@PathVariable String id, Model model) {
        Reconciliation run = reconciliation.find(id).orElseThrow(() -> ApiException.notFound("Reconciliation " + id));
        model.addAttribute("run", run);
        model.addAttribute("items", reconciliation.items(id));
        common(model);
        return "reconciliation";
    }

    /** Sandbox fault injection, to show what the poller and reconciliation catch. */
    @PostMapping("/sandbox/{action}")
    public String chaos(@PathVariable String action, @RequestParam(defaultValue = "WECHAT") Provider provider,
                        RedirectAttributes flash) {
        SandboxService s = sandbox.getIfAvailable();
        SandboxNotifier n = notifier.getIfAvailable();
        if (s == null || n == null) {
            flash.addFlashAttribute("error", "Sandbox is disabled in live mode");
            return "redirect:/admin/reconciliations";
        }
        switch (action) {
            case "drop-notification" -> {
                n.muteNext(1);
                flash.addFlashAttribute("ok", "The next notification will be dropped");
            }
            case "orphan-payment" -> {
                var t = s.orphanPayment(provider, 4500);
                flash.addFlashAttribute("ok", provider.label() + " now has a payment " + t.outTradeNo() + " we never created");
            }
            case "tamper-amount" -> flash.addFlashAttribute("ok", s.tamperLatestAmount(provider)
                    .map(t -> provider.label() + " now records " + t.outTradeNo() + " as ¥" + Money.toYuan(t.amountFen()))
                    .orElse("No paid " + provider.label() + " trade to tamper with"));
            default -> flash.addFlashAttribute("error", "Unknown action " + action);
        }
        return "redirect:/admin/reconciliations";
    }

    private void common(Model model) {
        model.addAttribute("sandbox", properties.sandboxMode());
    }
}
