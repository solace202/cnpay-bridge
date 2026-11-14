package dev.cnpay.refund;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class RefundRepository {

    private final JdbcClient jdbc;

    public RefundRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Refund r) {
        jdbc.sql("""
                insert into refunds (id, order_id, out_refund_no, amount_fen, reason, status, created_at)
                values (:id, :order, :no, :amount, :reason, 'PROCESSING', :created)
                """)
                .param("id", r.id()).param("order", r.orderId()).param("no", r.outRefundNo())
                .param("amount", r.amountFen()).param("reason", r.reason()).param("created", r.createdAt())
                .update();
    }

    public Optional<Refund> findByOutRefundNo(String outRefundNo) {
        return jdbc.sql("select * from refunds where out_refund_no = ?").param(outRefundNo)
                .query(Refund.class).optional();
    }

    public Optional<Refund> findById(String id) {
        return jdbc.sql("select * from refunds where id = ?").param(id).query(Refund.class).optional();
    }

    public List<Refund> forOrder(String orderId) {
        return jdbc.sql("select * from refunds where order_id = ? order by created_at, id").param(orderId)
                .query(Refund.class).list();
    }

    public long processingFen(String orderId) {
        return jdbc.sql("select coalesce(sum(amount_fen), 0) from refunds where order_id = ? and status = 'PROCESSING'")
                .param(orderId).query(Long.class).single();
    }

    public boolean markSucceeded(String outRefundNo, String providerRefundNo, LocalDateTime at) {
        return jdbc.sql("""
                update refunds set status = 'SUCCESS', provider_refund_no = coalesce(:prn, provider_refund_no),
                                   succeeded_at = :at
                 where out_refund_no = :no and status = 'PROCESSING'
                """)
                .param("prn", providerRefundNo).param("at", at).param("no", outRefundNo).update() == 1;
    }

    public void setProviderRefundNo(String outRefundNo, String providerRefundNo) {
        jdbc.sql("update refunds set provider_refund_no = :prn where out_refund_no = :no")
                .param("prn", providerRefundNo).param("no", outRefundNo).update();
    }

    public boolean markFailed(String outRefundNo) {
        return jdbc.sql("update refunds set status = 'FAILED' where out_refund_no = ? and status = 'PROCESSING'")
                .param(outRefundNo).update() == 1;
    }

    /** Successful refunds per out_trade_no on a bill date. */
    public Map<String, Long> succeededOn(String provider, LocalDate date) {
        Map<String, Long> result = new HashMap<>();
        jdbc.sql("""
                select o.out_trade_no, sum(r.amount_fen) as fen
                  from refunds r join orders o on o.id = r.order_id
                 where o.provider = :provider and r.status = 'SUCCESS'
                   and r.succeeded_at >= :from and r.succeeded_at < :to
                 group by o.out_trade_no
                """)
                .param("provider", provider)
                .param("from", date.atStartOfDay()).param("to", date.plusDays(1).atStartOfDay())
                .query((rs, n) -> result.put(rs.getString(1), rs.getLong(2))).list();
        return result;
    }
}
