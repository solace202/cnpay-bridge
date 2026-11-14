package dev.cnpay.recon;

import dev.cnpay.notify.EventResult;
import dev.cnpay.notify.PaymentEventRepository;
import dev.cnpay.order.Order;
import dev.cnpay.order.OrderRepository;
import dev.cnpay.provider.BillLine;
import dev.cnpay.provider.Provider;
import dev.cnpay.provider.ProviderRegistry;
import dev.cnpay.refund.RefundRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Daily reconciliation (对账): download the provider's trade bill, compare it with local orders and refunds,
 * auto-repair lost notifications and record everything else for review.
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final ProviderRegistry providers;
    private final OrderRepository orders;
    private final RefundRepository refunds;
    private final PaymentEventRepository events;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;

    public ReconciliationService(ProviderRegistry providers, OrderRepository orders, RefundRepository refunds,
                                 PaymentEventRepository events, JdbcClient jdbc, TransactionTemplate tx, Clock clock) {
        this.providers = providers;
        this.orders = orders;
        this.refunds = refunds;
        this.events = events;
        this.jdbc = jdbc;
        this.tx = tx;
        this.clock = clock;
    }

    /** Bills for day D are published from about 10:00 on D+1 (Beijing); run shortly after for both providers. */
    @Scheduled(cron = "${cnpay.reconciliation.cron:0 30 10 * * *}", zone = "Asia/Shanghai")
    public void nightly() {
        LocalDate yesterday = LocalDate.now(clock).minusDays(1);
        for (Provider provider : Provider.values()) {
            try {
                run(provider, yesterday);
            } catch (RuntimeException e) {
                log.error("Reconciliation of {} {} failed", provider, yesterday, e);
            }
        }
    }

    public Reconciliation run(Provider provider, LocalDate billDate) {
        List<BillLine> bill = providers.get(provider).downloadBill(billDate);

        Set<String> referenced = new HashSet<>();
        bill.forEach(l -> referenced.add(l.outTradeNo()));
        Map<String, Order> byTradeNo = new LinkedHashMap<>();
        orders.findByOutTradeNos(referenced).forEach(o -> byTradeNo.put(o.outTradeNo(), o));
        Set<String> paidOnDate = new HashSet<>();
        for (Order o : orders.paidOn(provider.name(), billDate)) {
            byTradeNo.put(o.outTradeNo(), o);
            paidOnDate.add(o.outTradeNo());
        }
        Map<String, Reconciler.Local> local = new LinkedHashMap<>();
        byTradeNo.values().stream()
                .filter(o -> o.provider() == provider)
                .forEach(o -> local.put(o.outTradeNo(), new Reconciler.Local(o.outTradeNo(), o.status(), o.amountFen(),
                        paidOnDate.contains(o.outTradeNo()))));

        Reconciler.Result result = Reconciler.compare(bill, local, refunds.succeededOn(provider.name(), billDate));

        return tx.execute(status -> {
            int repaired = 0;
            List<Difference> recorded = new ArrayList<>();
            for (Difference d : result.differences()) {
                if (d.kind() == Difference.Kind.STATUS_MISMATCH && d.action().startsWith("Auto-repair")) {
                    BillLine line = bill.stream().filter(l -> l.type() == BillLine.Type.PAYMENT
                            && l.outTradeNo().equals(d.outTradeNo())).findFirst().orElseThrow();
                    if (orders.markPaid(d.outTradeNo(), line.providerTradeNo(), null,
                            Optional.ofNullable(line.time()).orElse(LocalDateTime.now(clock)))) {
                        repaired++;
                        events.recordDelivery(provider, "recon-" + d.outTradeNo(), "RECONCILIATION.REPAIR",
                                d.outTradeNo(), true, EventResult.APPLIED,
                                d.localStatus() + " → PAID from " + billDate + " bill (" + line.providerTradeNo() + ")", null);
                        recorded.add(new Difference(d.kind(), d.outTradeNo(), d.localStatus(), d.localFen(),
                                d.remoteStatus(), d.remoteFen(), "Repaired: marked PAID from bill"));
                        continue;
                    }
                }
                recorded.add(d);
            }
            String id = UUID.randomUUID().toString();
            jdbc.sql("""
                    insert into reconciliations (id, provider, bill_date, status, remote_count, remote_fen, local_count,
                                                 local_fen, matched, differences, repaired, created_at)
                    values (:id, :provider, :date, :status, :rc, :rf, :lc, :lf, :matched, :diffs, :repaired, :created)
                    """)
                    .param("id", id).param("provider", provider.name()).param("date", billDate)
                    .param("status", recorded.isEmpty() ? "MATCHED" : "DIFFERENCES")
                    .param("rc", result.remoteCount()).param("rf", result.remoteFen())
                    .param("lc", result.localCount()).param("lf", result.localFen())
                    .param("matched", result.matched()).param("diffs", recorded.size()).param("repaired", repaired)
                    .param("created", LocalDateTime.now(clock))
                    .update();
            for (Difference d : recorded) {
                jdbc.sql("""
                        insert into reconciliation_items (reconciliation_id, out_trade_no, kind, local_status, local_fen,
                                                          remote_status, remote_fen, action)
                        values (?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                        .params(id, d.outTradeNo(), d.kind().name(), d.localStatus(), d.localFen(), d.remoteStatus(),
                                d.remoteFen(), d.action())
                        .update();
            }
            log.info("Reconciled {} {}: {} matched, {} difference(s), {} repaired", provider, billDate,
                    result.matched(), recorded.size(), repaired);
            return find(id).orElseThrow();
        });
    }

    public Optional<Reconciliation> find(String id) {
        return jdbc.sql("select * from reconciliations where id = ?").param(id).query(Reconciliation.class).optional();
    }

    public List<Reconciliation> recent(int limit) {
        return jdbc.sql("select * from reconciliations order by created_at desc limit ?").param(limit)
                .query(Reconciliation.class).list();
    }

    public List<ReconciliationItem> items(String reconciliationId) {
        return jdbc.sql("select * from reconciliation_items where reconciliation_id = ? order by kind, id")
                .param(reconciliationId).query(ReconciliationItem.class).list();
    }
}
