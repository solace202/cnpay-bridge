package dev.cnpay.sandbox;

import dev.cnpay.provider.Provider;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class SandboxLedger {

    private final JdbcClient jdbc;

    public SandboxLedger(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(SandboxTrade t) {
        jdbc.sql("""
                insert into sandbox_trades (provider, out_trade_no, trade_no, app_id, mch_id, trade_type, description,
                                            amount_fen, state, notify_url, return_url, payer, expires_at, paid_at, created_at)
                values (:provider, :otn, :tn, :app, :mch, :type, :desc, :amount, :state, :notify, :ret, :payer,
                        :expires, :paidAt, :created)
                """)
                .param("provider", t.provider().name()).param("otn", t.outTradeNo()).param("tn", t.tradeNo())
                .param("app", t.appId()).param("mch", t.mchId()).param("type", t.tradeType())
                .param("desc", t.description()).param("amount", t.amountFen()).param("state", t.state())
                .param("notify", t.notifyUrl()).param("ret", t.returnUrl()).param("payer", t.payer())
                .param("expires", t.expiresAt()).param("paidAt", t.paidAt()).param("created", t.createdAt())
                .update();
    }

    public Optional<SandboxTrade> find(Provider provider, String outTradeNo) {
        return jdbc.sql("select * from sandbox_trades where provider = ? and out_trade_no = ?")
                .params(provider.name(), outTradeNo).query(SandboxTrade.class).optional();
    }

    public boolean markPaid(Provider provider, String outTradeNo, String payer, LocalDateTime at) {
        return jdbc.sql("""
                update sandbox_trades set state = 'SUCCESS', payer = :payer, paid_at = :at
                 where provider = :p and out_trade_no = :otn and state = 'NOTPAY'
                """)
                .param("payer", payer).param("at", at).param("p", provider.name()).param("otn", outTradeNo)
                .update() == 1;
    }

    public boolean close(Provider provider, String outTradeNo) {
        return jdbc.sql("update sandbox_trades set state = 'CLOSED' where provider = ? and out_trade_no = ? and state = 'NOTPAY'")
                .params(provider.name(), outTradeNo).update() == 1;
    }

    public void addRefunded(Provider provider, String outTradeNo, long fen) {
        jdbc.sql("""
                update sandbox_trades set state = 'REFUND', refunded_fen = refunded_fen + :fen
                 where provider = :p and out_trade_no = :otn
                """)
                .param("fen", fen).param("p", provider.name()).param("otn", outTradeNo).update();
    }

    public void setAmount(Provider provider, String outTradeNo, long fen) {
        jdbc.sql("update sandbox_trades set amount_fen = :fen where provider = :p and out_trade_no = :otn")
                .param("fen", fen).param("p", provider.name()).param("otn", outTradeNo).update();
    }

    public Optional<SandboxTrade> latestPaid(Provider provider) {
        return jdbc.sql("""
                select * from sandbox_trades where provider = ? and state in ('SUCCESS', 'REFUND')
                 order by paid_at desc, created_at desc limit 1
                """).param(provider.name()).query(SandboxTrade.class).optional();
    }

    public List<SandboxTrade> paidOn(Provider provider, LocalDate date) {
        return jdbc.sql("""
                select * from sandbox_trades
                 where provider = :p and state in ('SUCCESS', 'REFUND') and paid_at >= :from and paid_at < :to
                 order by paid_at
                """)
                .param("p", provider.name()).param("from", date.atStartOfDay()).param("to", date.plusDays(1).atStartOfDay())
                .query(SandboxTrade.class).list();
    }

    public void insertRefund(SandboxRefund r) {
        jdbc.sql("""
                insert into sandbox_refunds (provider, out_refund_no, refund_id, out_trade_no, amount_fen, status,
                                             notify_url, created_at, succeeded_at)
                values (:p, :no, :id, :otn, :amount, :status, :notify, :created, :succeeded)
                """)
                .param("p", r.provider().name()).param("no", r.outRefundNo()).param("id", r.refundId())
                .param("otn", r.outTradeNo()).param("amount", r.amountFen()).param("status", r.status())
                .param("notify", r.notifyUrl()).param("created", r.createdAt()).param("succeeded", r.succeededAt())
                .update();
    }

    public Optional<SandboxRefund> findRefund(Provider provider, String outRefundNo) {
        return jdbc.sql("select * from sandbox_refunds where provider = ? and out_refund_no = ?")
                .params(provider.name(), outRefundNo).query(SandboxRefund.class).optional();
    }

    public long refundedOrPending(Provider provider, String outTradeNo) {
        return jdbc.sql("""
                select coalesce(sum(amount_fen), 0) from sandbox_refunds
                 where provider = ? and out_trade_no = ? and status in ('PROCESSING', 'SUCCESS')
                """).params(provider.name(), outTradeNo).query(Long.class).single();
    }

    public boolean markRefundSucceeded(Provider provider, String outRefundNo, LocalDateTime at) {
        return jdbc.sql("""
                update sandbox_refunds set status = 'SUCCESS', succeeded_at = :at
                 where provider = :p and out_refund_no = :no and status = 'PROCESSING'
                """)
                .param("at", at).param("p", provider.name()).param("no", outRefundNo).update() == 1;
    }

    public List<SandboxRefund> refundsSucceededOn(Provider provider, LocalDate date) {
        return jdbc.sql("""
                select * from sandbox_refunds
                 where provider = :p and status = 'SUCCESS' and succeeded_at >= :from and succeeded_at < :to
                 order by succeeded_at
                """)
                .param("p", provider.name()).param("from", date.atStartOfDay()).param("to", date.plusDays(1).atStartOfDay())
                .query(SandboxRefund.class).list();
    }
}
