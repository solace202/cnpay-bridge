package dev.cnpay.provider.alipay;

import dev.cnpay.money.Money;
import dev.cnpay.provider.BillLine;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Parses the Alipay trade bill: a zip holding a GBK-encoded "..._业务明细.csv" (details) and a "..._汇总.csv"
 * (summary). Lines starting with '#' are banners; cells are padded with spaces and tabs; refunds have
 * 业务类型 = 退款 and negative amounts.
 */
public final class AlipayBillParser {

    private static final Charset GBK = Charset.forName("GBK");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private AlipayBillParser() {
    }

    public static List<BillLine> parseZip(byte[] zip) {
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip), GBK)) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                String name = entry.getName();
                if (name.endsWith(".csv") && name.contains("业务明细") && !name.contains("汇总")) {
                    return parseCsv(new String(in.readAllBytes(), GBK));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Corrupt Alipay bill archive", e);
        }
        throw new IllegalArgumentException("Alipay bill archive has no 业务明细 CSV");
    }

    public static List<BillLine> parseCsv(String csv) {
        List<BillLine> result = new ArrayList<>();
        Map<String, Integer> col = null;
        for (String raw : csv.split("\r?\n")) {
            String line = raw.strip();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("#")) {
                if (col != null) {
                    break; // trailing "#-----业务明细列表结束-----" banner
                }
                continue;
            }
            String[] cells = line.split(",", -1);
            if (col == null) {
                col = header(cells);
                continue;
            }
            for (int i = 0; i < cells.length; i++) {
                cells[i] = cells[i].strip();
            }
            String type = cells[col.get("业务类型")];
            String outTradeNo = cells[col.get("商户订单号")];
            String tradeNo = cells[col.get("支付宝交易号")];
            long fen = Math.abs(Money.parseBillYuan(cells[col.get("订单金额（元）")]));
            String finished = cells[col.get("完成时间")];
            LocalDateTime time = finished.isEmpty() ? null : LocalDateTime.parse(finished, TIME);
            if ("交易".equals(type)) {
                result.add(new BillLine(BillLine.Type.PAYMENT, outTradeNo, tradeNo, fen, null, time));
            } else if ("退款".equals(type)) {
                result.add(new BillLine(BillLine.Type.REFUND, outTradeNo, tradeNo, fen,
                        cells[col.get("退款批次号/请求号")], time));
            }
        }
        if (col == null) {
            throw new IllegalArgumentException("Alipay bill has no header row");
        }
        return result;
    }

    private static Map<String, Integer> header(String[] cells) {
        Map<String, Integer> col = new HashMap<>();
        for (int i = 0; i < cells.length; i++) {
            col.put(cells[i].strip(), i);
        }
        for (String required : List.of("支付宝交易号", "商户订单号", "业务类型", "完成时间", "订单金额（元）", "退款批次号/请求号")) {
            if (!col.containsKey(required)) {
                throw new IllegalArgumentException("Alipay bill is missing column " + required);
            }
        }
        return col;
    }
}
