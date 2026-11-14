package dev.cnpay.recon;

import dev.cnpay.money.Money;
import dev.cnpay.order.OrderStatus;
import dev.cnpay.provider.BillLine;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/** Pure comparison of a provider bill against local records; no I/O, so every rule is unit-tested. */
public final class Reconciler {

    private Reconciler() {
    }

    /** Our view of an order relevant to the bill date. */
    public record Local(String outTradeNo, OrderStatus status, long amountFen, boolean paidOnBillDate) {
    }

    public record Result(int remoteCount, long remoteFen, int localCount, long localFen, int matched,
                         List<Difference> differences) {
    }

    public static Result compare(List<BillLine> bill, Map<String, Local> local, Map<String, Long> localRefunds) {
        Map<String, BillLine> payments = new LinkedHashMap<>();
        Map<String, Long> remoteRefunds = new LinkedHashMap<>();
        for (BillLine line : bill) {
            if (line.type() == BillLine.Type.PAYMENT) {
                payments.put(line.outTradeNo(), line);
            } else {
                remoteRefunds.merge(line.outTradeNo(), line.amountFen(), Long::sum);
            }
        }

        List<Difference> diffs = new ArrayList<>();
        int matched = 0;
        for (BillLine p : payments.values()) {
            Local l = local.get(p.outTradeNo());
            if (l == null) {
                diffs.add(new Difference(Difference.Kind.MISSING_LOCAL, p.outTradeNo(), null, null, "PAID", p.amountFen(),
                        "Investigate: provider took " + Money.display(p.amountFen()) + " for an order we never created"));
            } else if (!l.status().paid()) {
                diffs.add(new Difference(Difference.Kind.STATUS_MISMATCH, p.outTradeNo(), l.status().name(), l.amountFen(),
                        "PAID", p.amountFen(), l.amountFen() == p.amountFen()
                        ? "Auto-repair: mark PAID from bill (notification was lost)"
                        : "Manual review: amounts also differ"));
            } else if (l.amountFen() != p.amountFen()) {
                diffs.add(new Difference(Difference.Kind.AMOUNT_MISMATCH, p.outTradeNo(), l.status().name(), l.amountFen(),
                        "PAID", p.amountFen(), "Manual review: provider amount differs by "
                        + Money.display(Math.abs(p.amountFen() - l.amountFen()))));
            } else {
                matched++;
            }
        }
        int localCount = 0;
        long localFen = 0;
        for (Local l : local.values()) {
            if (!l.paidOnBillDate()) {
                continue;
            }
            localCount++;
            localFen += l.amountFen();
            if (!payments.containsKey(l.outTradeNo())) {
                diffs.add(new Difference(Difference.Kind.MISSING_REMOTE, l.outTradeNo(), l.status().name(), l.amountFen(),
                        null, null, "Escalate: marked paid locally but absent from the provider bill"));
            }
        }
        for (String otn : new TreeSet<>(union(remoteRefunds.keySet(), localRefunds.keySet()))) {
            long remote = remoteRefunds.getOrDefault(otn, 0L);
            long mine = localRefunds.getOrDefault(otn, 0L);
            if (remote != mine) {
                Local l = local.get(otn);
                diffs.add(new Difference(Difference.Kind.REFUND_MISMATCH, otn, l == null ? null : l.status().name(), mine,
                        "REFUNDED", remote, "Manual review: refunds differ ("
                        + Money.display(mine) + " local vs " + Money.display(remote) + " provider)"));
            }
        }
        long remoteFen = payments.values().stream().mapToLong(BillLine::amountFen).sum();
        return new Result(payments.size(), remoteFen, localCount, localFen, matched, List.copyOf(diffs));
    }

    private static TreeSet<String> union(java.util.Set<String> a, java.util.Set<String> b) {
        TreeSet<String> all = new TreeSet<>(a);
        all.addAll(b);
        all.removeIf(Objects::isNull);
        return all;
    }
}
