package dev.cnpay.provider.wechat;

import dev.cnpay.money.Money;
import dev.cnpay.provider.BillLine;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses the WeChat Pay trade bill (bill_type=ALL): a CSV whose cells are prefixed with a back-tick, a header
 * row in Chinese, the detail rows, then a "总交易单数" summary block. Columns are located by header name so the
 * parser survives the extra columns WeChat adds over time.
 */
public final class WechatBillParser {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private WechatBillParser() {
    }

    public static List<BillLine> parse(String csv) {
        String[] lines = csv.replace("\uFEFF", "").split("\r?\n");
        if (lines.length == 0 || !lines[0].contains("商户订单号")) {
            throw new IllegalArgumentException("Not a WeChat Pay trade bill");
        }
        Map<String, Integer> col = header(lines[0]);
        List<BillLine> result = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isBlank()) {
                continue;
            }
            if (line.startsWith("总交易单数")) {
                break;
            }
            String[] cells = cells(line);
            String state = cell(cells, col, "交易状态");
            LocalDateTime time = LocalDateTime.parse(cell(cells, col, "交易时间"), TIME);
            String outTradeNo = cell(cells, col, "商户订单号");
            String transactionId = cell(cells, col, "微信订单号");
            if ("REFUND".equals(state)) {
                result.add(new BillLine(BillLine.Type.REFUND, outTradeNo, transactionId,
                        Money.parseBillYuan(cell(cells, col, "退款金额")), cell(cells, col, "商户退款单号"), time));
            } else if ("SUCCESS".equals(state)) {
                result.add(new BillLine(BillLine.Type.PAYMENT, outTradeNo, transactionId,
                        Money.parseBillYuan(cell(cells, col, "订单金额")), null, time));
            }
        }
        return result;
    }

    private static Map<String, Integer> header(String line) {
        Map<String, Integer> col = new HashMap<>();
        String[] names = line.split(",", -1);
        for (int i = 0; i < names.length; i++) {
            col.put(names[i].trim(), i);
        }
        for (String required : List.of("交易时间", "微信订单号", "商户订单号", "交易状态", "订单金额", "退款金额", "商户退款单号")) {
            if (!col.containsKey(required)) {
                throw new IllegalArgumentException("WeChat bill is missing column " + required);
            }
        }
        return col;
    }

    private static String[] cells(String line) {
        String[] cells = line.split(",", -1);
        for (int i = 0; i < cells.length; i++) {
            cells[i] = cells[i].startsWith("`") ? cells[i].substring(1).trim() : cells[i].trim();
        }
        return cells;
    }

    private static String cell(String[] cells, Map<String, Integer> col, String name) {
        int i = col.get(name);
        return i < cells.length ? cells[i] : "";
    }
}
