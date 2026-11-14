package dev.cnpay.provider;

import dev.cnpay.order.Order;
import dev.cnpay.refund.Refund;
import java.time.LocalDate;
import java.util.List;

public interface PaymentProvider {

    Provider id();

    PayInstruction create(Order order);

    TradeSnapshot query(String outTradeNo);

    void close(String outTradeNo);

    RefundOutcome refund(Order order, Refund refund);

    /** The day's trade bill: payments and refunds, Beijing time. */
    List<BillLine> downloadBill(LocalDate billDate);
}
