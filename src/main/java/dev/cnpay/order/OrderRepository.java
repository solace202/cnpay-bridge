package dev.cnpay.order;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Plain SQL. Every state change is a conditional UPDATE ("... where status = 'PENDING'") so that callbacks,
 * the poller and the reconciliation job can race without double-applying anything.
 */
@Repository
public class OrderRepository {

    private final JdbcClient jdbc;

    public OrderRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Order o) {
        jdbc.sql("""
                insert into orders (id, merchant_order_no, out_trade_no, provider, channel, description, amount_fen,
                                    currency, status, payer_openid, client_ip, expires_at, created_at, updated_at)
                values (:id, :mon, :otn, :provider, :channel, :description, :amount, 'CNY', 'PENDING',
                        :openid, :ip, :expires, :created, :created)
                """)
                .param("id", o.id()).param("mon", o.merchantOrderNo()).param("otn", o.outTradeNo())
                .param("provider", o.provider().name()).param("channel", o.channel().name())
                .param("description", o.description()).param("amount", o.amountFen())
                .param("openid", o.payerOpenid()).param("ip", o.clientIp())
                .param("expires", o.expiresAt()).param("created", o.createdAt())
                .update();
    }

    public Optional<Order> findById(String id) {
        return jdbc.sql("select * from orders where id = ?").param(id).query(Order.class).optional();
    }

    public Optional<Order> findByOutTradeNo(String outTradeNo) {
        return jdbc.sql("select * from orders where out_trade_no = ?").param(outTradeNo).query(Order.class).optional();
    }

    public Optional<Order> findByMerchantOrderNo(String merchantOrderNo) {
        return jdbc.sql("select * from orders where merchant_order_no = ?").param(merchantOrderNo)
                .query(Order.class).optional();
    }

    public Optional<Order> lockById(String id) {
        return jdbc.sql("select * from orders where id = ? for update").param(id).query(Order.class).optional();
    }

    public List<Order> recent(int limit) {
        return jdbc.sql("select * from orders order by created_at desc, id limit ?").param(limit)
                .query(Order.class).list();
    }

    public List<Order> findByOutTradeNos(Collection<String> outTradeNos) {
        if (outTradeNos.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("select * from orders where out_trade_no in (:nos)").param("nos", outTradeNos)
                .query(Order.class).list();
    }

    /** Orders the merchant believes were paid on a bill date (Beijing time). */
    public List<Order> paidOn(String provider, LocalDate date) {
        return jdbc.sql("""
                select * from orders
                 where provider = :provider and paid_at >= :from and paid_at < :to
                   and status in ('PAID', 'PARTIALLY_REFUNDED', 'REFUNDED')
                """)
                .param("provider", provider)
                .param("from", date.atStartOfDay()).param("to", date.plusDays(1).atStartOfDay())
                .query(Order.class).list();
    }

    /** Pending orders old enough that a callback should have arrived, oldest first. */
    public List<Order> pendingOlderThan(LocalDateTime createdBefore, int limit) {
        return jdbc.sql("""
                select * from orders where status = 'PENDING' and created_at < :before
                 order by created_at limit :limit
                """)
                .param("before", createdBefore).param("limit", limit)
                .query(Order.class).list();
    }

    public void savePayload(String id, String json) {
        jdbc.sql("update orders set pay_payload = :p where id = :id").param("p", json).param("id", id).update();
    }

    /**
     * PENDING (or locally CLOSED: the customer paid just before we closed it) → PAID.
     * Returns false when the order was already paid, which is how duplicate callbacks become no-ops.
     */
    public boolean markPaid(String outTradeNo, String providerTradeNo, String payer, LocalDateTime paidAt) {
        return jdbc.sql("""
                update orders set status = 'PAID', provider_trade_no = :tn, payer_account = :payer, paid_at = :paidAt,
                                  closed_at = null
                 where out_trade_no = :otn and status in ('PENDING', 'CLOSED')
                """)
                .param("tn", providerTradeNo).param("payer", payer).param("paidAt", paidAt).param("otn", outTradeNo)
                .update() == 1;
    }

    public boolean markClosed(String id, LocalDateTime closedAt) {
        return jdbc.sql("update orders set status = 'CLOSED', closed_at = :at where id = :id and status = 'PENDING'")
                .param("at", closedAt).param("id", id).update() == 1;
    }

    /** MySQL evaluates SET left to right, so the status expression must come before refunded_fen changes. */
    public void addRefunded(String id, long fen) {
        jdbc.sql("""
                update orders
                   set status = case when refunded_fen + :fen >= amount_fen then 'REFUNDED' else 'PARTIALLY_REFUNDED' end,
                       refunded_fen = refunded_fen + :fen
                 where id = :id
                """)
                .param("fen", fen).param("id", id).update();
    }
}
