package dev.cnpay;

import static org.assertj.core.api.Assertions.assertThat;

import dev.cnpay.order.OrderStatus;
import dev.cnpay.provider.BillLine;
import dev.cnpay.recon.Difference;
import dev.cnpay.recon.Reconciler;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReconcilerTest {

    private static BillLine pay(String otn, long fen) {
        return new BillLine(BillLine.Type.PAYMENT, otn, "T" + otn, fen, null, LocalDateTime.of(2026, 11, 13, 10, 0));
    }

    private static BillLine refund(String otn, long fen) {
        return new BillLine(BillLine.Type.REFUND, otn, "T" + otn, fen, "R" + otn, LocalDateTime.of(2026, 11, 13, 12, 0));
    }

    private static Reconciler.Local local(String otn, OrderStatus s, long fen, boolean paidToday) {
        return new Reconciler.Local(otn, s, fen, paidToday);
    }

    @Test
    void identicalRecordsMatch() {
        var r = Reconciler.compare(List.of(pay("A", 100), pay("B", 250)),
                Map.of("A", local("A", OrderStatus.PAID, 100, true), "B", local("B", OrderStatus.PAID, 250, true)), Map.of());
        assertThat(r.matched()).isEqualTo(2);
        assertThat(r.differences()).isEmpty();
        assertThat(r.remoteFen()).isEqualTo(350);
        assertThat(r.localFen()).isEqualTo(350);
    }

    @Test
    void pendingLocallyButPaidAtProviderIsARepairableStatusMismatch() {
        var r = Reconciler.compare(List.of(pay("A", 100)), Map.of("A", local("A", OrderStatus.PENDING, 100, false)), Map.of());
        assertThat(r.differences()).singleElement().satisfies(d -> {
            assertThat(d.kind()).isEqualTo(Difference.Kind.STATUS_MISMATCH);
            assertThat(d.action()).startsWith("Auto-repair");
        });
    }

    @Test
    void unknownRemotePaymentAndMissingRemotePaymentAreFlagged() {
        var r = Reconciler.compare(List.of(pay("X", 999)), Map.of("L", local("L", OrderStatus.PAID, 500, true)), Map.of());
        assertThat(r.differences()).extracting(Difference::kind, Difference::outTradeNo)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(Difference.Kind.MISSING_LOCAL, "X"),
                        org.assertj.core.groups.Tuple.tuple(Difference.Kind.MISSING_REMOTE, "L"));
    }

    @Test
    void amountDifferencesNeedManualReview() {
        var r = Reconciler.compare(List.of(pay("A", 200)), Map.of("A", local("A", OrderStatus.PAID, 100, true)), Map.of());
        assertThat(r.differences()).singleElement().satisfies(d -> {
            assertThat(d.kind()).isEqualTo(Difference.Kind.AMOUNT_MISMATCH);
            assertThat(d.action()).contains("¥1.00");
        });
    }

    @Test
    void refundTotalsAreComparedPerTrade() {
        var bill = List.of(pay("A", 1000), refund("A", 200), refund("A", 300), refund("B", 50));
        var locals = Map.of("A", local("A", OrderStatus.PARTIALLY_REFUNDED, 1000, true));
        var r = Reconciler.compare(bill, locals, Map.of("A", 500L));
        assertThat(r.differences()).singleElement().satisfies(d -> {
            assertThat(d.kind()).isEqualTo(Difference.Kind.REFUND_MISMATCH);
            assertThat(d.outTradeNo()).isEqualTo("B");
        });
    }
}
