package dev.cnpay.sandbox;

import dev.cnpay.money.Money;
import dev.cnpay.provider.Provider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.StringJoiner;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Produces daily bills in the providers' real file formats, from the sandbox ledger. */
@Component
@ConditionalOnProperty(name = "cnpay.mode", havingValue = "sandbox", matchIfMissing = true)
public class SandboxBills {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Charset GBK = Charset.forName("GBK");
    static final String WECHAT_HEADER = "交易时间,公众账号ID,商户号,特约商户号,设备号,微信订单号,商户订单号,用户标识,交易类型,交易状态,"
            + "付款银行,货币种类,应结订单金额,代金券金额,微信退款单号,商户退款单号,退款金额,充值券退款金额,退款类型,退款状态,商品名称,"
            + "商户数据包,手续费,费率,订单金额,申请退款金额,费率备注";
    static final String ALIPAY_HEADER = "支付宝交易号,商户订单号,业务类型,商品名称,创建时间,完成时间,门店编号,门店名称,操作员,终端号,"
            + "对方账户,订单金额（元）,商家实收（元）,支付宝红包（元）,集分宝（元）,支付宝优惠（元）,商家优惠（元）,券核销金额（元）,券名称,"
            + "商家红包消费金额（元）,卡消费金额（元）,退款批次号/请求号,服务费（元）,分润（元）,备注";

    private final SandboxLedger ledger;

    public SandboxBills(SandboxLedger ledger) {
        this.ledger = ledger;
    }

    /** WeChat Pay "ALL" trade bill: every cell prefixed with a back-tick, then a summary block. */
    public String wechatTradeBill(LocalDate date) {
        List<SandboxTrade> trades = ledger.paidOn(Provider.WECHAT, date);
        List<SandboxRefund> refunds = ledger.refundsSucceededOn(Provider.WECHAT, date);
        StringBuilder csv = new StringBuilder(WECHAT_HEADER).append("\r\n");
        long settled = 0;
        long refunded = 0;
        long fees = 0;
        for (SandboxTrade t : trades) {
            long fee = Math.round(t.amountFen() * 0.006);
            settled += t.amountFen();
            fees += fee;
            csv.append(wxRow(t.paidAt().format(TIME), t.appId(), t.mchId(), t.tradeNo(), t.outTradeNo(), t.payer(),
                    t.tradeType(), "SUCCESS", Money.toYuan(t.amountFen()), "0", "0", "0.00", "", "",
                    t.description(), Money.toYuan(fee), Money.toYuan(t.amountFen()), "0.00"));
        }
        for (SandboxRefund r : refunds) {
            SandboxTrade t = ledger.find(Provider.WECHAT, r.outTradeNo()).orElseThrow();
            long fee = -Math.round(r.amountFen() * 0.006);
            refunded += r.amountFen();
            fees += fee;
            csv.append(wxRow(r.succeededAt().format(TIME), t.appId(), t.mchId(), t.tradeNo(), t.outTradeNo(), t.payer(),
                    t.tradeType(), "REFUND", Money.toYuan(t.amountFen()), r.refundId(), r.outRefundNo(),
                    Money.toYuan(r.amountFen()), "ORIGINAL", "SUCCESS", t.description(), Money.toYuan(fee),
                    Money.toYuan(t.amountFen()), Money.toYuan(r.amountFen())));
        }
        csv.append("总交易单数,应结订单总金额,退款总金额,充值券退款总金额,手续费总金额,订单总金额,申请退款总金额\r\n");
        csv.append('`').append(trades.size() + refunds.size())
                .append(",`").append(Money.toYuan(settled))
                .append(",`").append(Money.toYuan(refunded))
                .append(",`0.00,`").append(Money.toYuan(fees))
                .append(",`").append(Money.toYuan(settled))
                .append(",`").append(Money.toYuan(refunded)).append("\r\n");
        return csv.toString();
    }

    private static String wxRow(String time, String appId, String mchId, String transactionId, String outTradeNo,
                                String openid, String tradeType, String state, String settle, String refundId,
                                String outRefundNo, String refundFee, String refundType, String refundStatus,
                                String description, String fee, String orderAmount, String appliedRefund) {
        StringJoiner row = new StringJoiner(",`", "`", "\r\n");
        for (String cell : new String[] {time, appId, mchId, "0", "", transactionId, outTradeNo, openid, tradeType, state,
                "OTHERS", "CNY", settle, "0.00", refundId, outRefundNo, refundFee, "0.00", refundType, refundStatus,
                description.replace(",", " "), "", fee, "0.60%", orderAmount, appliedRefund, ""}) {
            row.add(cell == null ? "" : cell);
        }
        return row.toString();
    }

    /** Alipay trade bill: a zip with a GBK detail CSV and a summary CSV, '#' banner lines, tab-padded cells. */
    public byte[] alipayTradeBillZip(LocalDate date) {
        String day = date.format(DateTimeFormatter.BASIC_ISO_DATE);
        String account = "2088000000000001";
        List<SandboxTrade> trades = ledger.paidOn(Provider.ALIPAY, date);
        List<SandboxRefund> refunds = ledger.refundsSucceededOn(Provider.ALIPAY, date);
        StringBuilder detail = new StringBuilder();
        detail.append("#支付宝交易记录明细查询\r\n#账号：[").append(account).append("0156]\r\n")
                .append("#起始日期：[").append(date).append(" 00:00:00]   终止日期：[").append(date.plusDays(1)).append(" 00:00:00]\r\n")
                .append("#-----------------------------------------业务明细列表----------------------------------------\r\n")
                .append(ALIPAY_HEADER).append("\r\n");
        long total = 0;
        for (SandboxTrade t : trades) {
            total += t.amountFen();
            String amount = Money.toYuan(t.amountFen());
            detail.append(aliRow(t.tradeNo(), t.outTradeNo(), "交易", t.description(), t.createdAt().format(TIME),
                    t.paidAt().format(TIME), t.payer(), amount, amount, "",
                    "-" + Money.toYuan(Math.round(t.amountFen() * 0.006))));
        }
        long refundTotal = 0;
        for (SandboxRefund r : refunds) {
            SandboxTrade t = ledger.find(Provider.ALIPAY, r.outTradeNo()).orElseThrow();
            refundTotal += r.amountFen();
            String amount = "-" + Money.toYuan(r.amountFen());
            detail.append(aliRow(t.tradeNo(), t.outTradeNo(), "退款", t.description(), t.createdAt().format(TIME),
                    r.succeededAt().format(TIME), t.payer(), amount, amount, r.outRefundNo(),
                    Money.toYuan(Math.round(r.amountFen() * 0.006))));
        }
        detail.append("#-----------------------------------------业务明细列表结束------------------------------------\r\n")
                .append("#交易合计：").append(trades.size()).append("笔，商家实收共").append(Money.toYuan(total)).append("元\r\n")
                .append("#退款合计：").append(refunds.size()).append("笔，商家实收退款共").append(Money.toYuan(refundTotal)).append("元\r\n")
                .append("#导出时间：[").append(date.plusDays(1)).append(" 09:00:00]\r\n");
        String summary = "#支付宝业务汇总查询\r\n门店编号,门店名称,交易订单总笔数,退款订单总笔数,订单金额（元）,商家实收（元）\r\n"
                + "合计," + "," + trades.size() + "," + refunds.size() + "," + Money.toYuan(total) + ","
                + Money.toYuan(total - refundTotal) + "\r\n";

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out, GBK)) {
            zip.putNextEntry(new ZipEntry(account + "0156_" + day + "_业务明细.csv"));
            zip.write(detail.toString().getBytes(GBK));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry(account + "0156_" + day + "_业务明细(汇总).csv"));
            zip.write(summary.getBytes(GBK));
            zip.closeEntry();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    private static String aliRow(String tradeNo, String outTradeNo, String type, String subject, String created,
                                 String finished, String buyer, String amount, String received, String requestNo,
                                 String serviceFee) {
        StringJoiner row = new StringJoiner("\t,", "", "\t\r\n");
        for (String cell : new String[] {tradeNo, outTradeNo, type, subject.replace(",", " "), created, finished, "", "",
                "", "", buyer, amount, received, "0.00", "0.00", "0.00", "0.00", "0.00", "", "0.00", "0.00",
                requestNo, serviceFee, "0.00", ""}) {
            row.add(cell == null ? "" : cell);
        }
        return row.toString();
    }
}
