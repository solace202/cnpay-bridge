package dev.cnpay.notify;

import dev.cnpay.provider.Provider;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class PaymentEventRepository {

    private static final String COLUMNS = """
            id, provider, notify_id, event_type, out_trade_no, signature_valid, result, detail,
            cast(payload as char) as payload, deliveries, received_at""";

    private final JdbcClient jdbc;

    public PaymentEventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts the event, or bumps {@code deliveries} if (provider, notify_id) was seen before.
     * MySQL reports 1 affected row for an insert and 2 for the ON DUPLICATE KEY UPDATE path.
     *
     * @return true for the first delivery
     */
    public boolean recordDelivery(Provider provider, String notifyId, String eventType, String outTradeNo,
                                  boolean signatureValid, EventResult result, String detail, String payload) {
        int affected = jdbc.sql("""
                insert into payment_events (provider, notify_id, event_type, out_trade_no, signature_valid, result,
                                            detail, payload)
                values (:provider, :notifyId, :type, :otn, :valid, :result, :detail, :payload)
                on duplicate key update deliveries = deliveries + 1
                """)
                .param("provider", provider.name()).param("notifyId", notifyId).param("type", eventType)
                .param("otn", outTradeNo).param("valid", signatureValid).param("result", result.name())
                .param("detail", truncate(detail)).param("payload", payload)
                .update();
        return affected == 1;
    }

    public void setResult(Provider provider, String notifyId, EventResult result, String detail) {
        jdbc.sql("update payment_events set result = :result, detail = :detail where provider = :p and notify_id = :id")
                .param("result", result.name()).param("detail", truncate(detail))
                .param("p", provider.name()).param("id", notifyId).update();
    }

    public Optional<PaymentEvent> find(Provider provider, String notifyId) {
        return jdbc.sql("select " + COLUMNS + " from payment_events where provider = ? and notify_id = ?")
                .params(provider.name(), notifyId).query(PaymentEvent.class).optional();
    }

    public List<PaymentEvent> recent(int limit) {
        return jdbc.sql("select " + COLUMNS + " from payment_events order by id desc limit ?")
                .param(limit).query(PaymentEvent.class).list();
    }

    public List<PaymentEvent> forTrade(String outTradeNo) {
        return jdbc.sql("select " + COLUMNS + " from payment_events where out_trade_no = ? order by id")
                .param(outTradeNo).query(PaymentEvent.class).list();
    }

    private static String truncate(String s) {
        return s == null || s.length() <= 500 ? s : s.substring(0, 497) + "...";
    }
}
