package dev.cnpay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.cnpay.provider.BillLine;
import dev.cnpay.provider.alipay.AlipayBillParser;
import dev.cnpay.provider.wechat.WechatBillParser;
import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

class BillParserTest {

    private static final String WX_HEADER = "交易时间,公众账号ID,商户号,特约商户号,设备号,微信订单号,商户订单号,用户标识,交易类型,交易状态,"
            + "付款银行,货币种类,应结订单金额,代金券金额,微信退款单号,商户退款单号,退款金额,充值券退款金额,退款类型,退款状态,商品名称,"
            + "商户数据包,手续费,费率,订单金额,申请退款金额,费率备注";

    @Test
    void parsesWechatBacktickCsvIncludingRefundRowsAndStopsAtSummary() {
        String csv = WX_HEADER + "\r\n"
                + "`2026-11-13 10:00:01,`wx1,`1900000001,`0,`,`4200001,`CN001,`oA,`NATIVE,`SUCCESS,`OTHERS,`CNY,`88.00,`0.00,`0,`0,`0.00,`0.00,`,`,`Tea,`,`0.53,`0.60%,`88.00,`0.00,`\r\n"
                + "`2026-11-13 11:00:00,`wx1,`1900000001,`0,`,`4200001,`CN001,`oA,`NATIVE,`REFUND,`OTHERS,`CNY,`88.00,`0.00,`50300001,`RF001,`20.00,`0.00,`ORIGINAL,`SUCCESS,`Tea,`,`-0.12,`0.60%,`88.00,`20.00,`\r\n"
                + "总交易单数,应结订单总金额,退款总金额,充值券退款总金额,手续费总金额,订单总金额,申请退款总金额\r\n"
                + "`2,`88.00,`20.00,`0.00,`0.41,`88.00,`20.00\r\n";
        List<BillLine> lines = WechatBillParser.parse(csv);
        assertThat(lines).extracting(BillLine::type, BillLine::outTradeNo, BillLine::amountFen, BillLine::outRefundNo)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(BillLine.Type.PAYMENT, "CN001", 8800L, null),
                        org.assertj.core.groups.Tuple.tuple(BillLine.Type.REFUND, "CN001", 2000L, "RF001"));
        assertThatThrownBy(() -> WechatBillParser.parse("hello,world\n")).hasMessageContaining("Not a WeChat");
    }

    @Test
    void parsesAlipayGbkZipWithBannersAndPaddedCells() throws Exception {
        Charset gbk = Charset.forName("GBK");
        String header = "支付宝交易号,商户订单号,业务类型,商品名称,创建时间,完成时间,门店编号,门店名称,操作员,终端号,对方账户,订单金额（元）,"
                + "商家实收（元）,支付宝红包（元）,集分宝（元）,支付宝优惠（元）,商家优惠（元）,券核销金额（元）,券名称,商家红包消费金额（元）,"
                + "卡消费金额（元）,退款批次号/请求号,服务费（元）,分润（元）,备注";
        String detail = "#支付宝交易记录明细查询\r\n#账号：[20880000000000010156]\r\n#----业务明细列表----\r\n" + header + "\r\n"
                + "2026111322001\t,CN002\t,交易\t,抹茶\t,2026-11-13 09:00:00\t,2026-11-13 09:00:05\t,\t,\t,\t,\t,138****8888\t,66.60\t,66.60\t,0.00\t,0.00\t,0.00\t,0.00\t,0.00\t,\t,0.00\t,0.00\t,\t,-0.40\t,0.00\t,\t\r\n"
                + "2026111322001\t,CN002\t,退款\t,抹茶\t,2026-11-13 09:00:00\t,2026-11-13 12:00:00\t,\t,\t,\t,\t,138****8888\t,-6.60\t,-6.60\t,0.00\t,0.00\t,0.00\t,0.00\t,0.00\t,\t,0.00\t,0.00\t,RF002\t,0.04\t,0.00\t,\t\r\n"
                + "#----业务明细列表结束----\r\n#交易合计：1笔\r\n";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out, gbk)) {
            zip.putNextEntry(new ZipEntry("20880000000000010156_20261113_业务明细(汇总).csv"));
            zip.write("#summary\r\n".getBytes(gbk));
            zip.putNextEntry(new ZipEntry("20880000000000010156_20261113_业务明细.csv"));
            zip.write(detail.getBytes(gbk));
        }
        List<BillLine> lines = AlipayBillParser.parseZip(out.toByteArray());
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0).type()).isEqualTo(BillLine.Type.PAYMENT);
        assertThat(lines.get(0).amountFen()).isEqualTo(6660);
        assertThat(lines.get(1).type()).isEqualTo(BillLine.Type.REFUND);
        assertThat(lines.get(1).amountFen()).isEqualTo(660);
        assertThat(lines.get(1).outRefundNo()).isEqualTo("RF002");
    }
}
