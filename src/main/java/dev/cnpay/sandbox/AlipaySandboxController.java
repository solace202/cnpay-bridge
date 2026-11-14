package dev.cnpay.sandbox;

import com.alipay.api.AlipayApiException;
import com.alipay.api.internal.util.AlipaySignature;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cnpay.config.CnpayProperties;
import dev.cnpay.crypto.KeyRing;
import dev.cnpay.crypto.Keys;
import dev.cnpay.money.Money;
import dev.cnpay.provider.Provider;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Plays openapi.alipay.com/gateway.do for the official SDK: verifies the RSA2 request signature over the sorted
 * parameters, dispatches on {@code method}, and returns {@code {"<method>_response": {...}, "sign": "..."}} where
 * the signature covers the exact JSON text of the response object — which is what the SDK verifies.
 */
@Hidden
@RestController
@RequestMapping("/sandbox/alipay")
@ConditionalOnProperty(name = "cnpay.mode", havingValue = "sandbox", matchIfMissing = true)
public class AlipaySandboxController {

    private static final DateTimeFormatter ALIPAY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final SandboxService sandbox;
    private final SandboxLedger ledger;
    private final SandboxBills bills;
    private final KeyRing keys;
    private final CnpayProperties properties;
    private final ObjectMapper mapper;

    public AlipaySandboxController(SandboxService sandbox, SandboxLedger ledger, SandboxBills bills, KeyRing keys,
                                   CnpayProperties properties, ObjectMapper mapper) {
        this.sandbox = sandbox;
        this.ledger = ledger;
        this.bills = bills;
        this.keys = keys;
        this.properties = properties;
        this.mapper = mapper;
    }

    @RequestMapping(value = "/gateway.do", method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<String> gateway(HttpServletRequest request) {
        Map<String, String> params = new TreeMap<>();
        request.getParameterMap().forEach((k, v) -> params.put(k, v[0]));
        String method = params.getOrDefault("method", "");
        String root = method.replace('.', '_') + "_response";

        if (!properties.alipay().appId().equals(params.get("app_id"))) {
            return respond("error_response", fail("40002", "Invalid Arguments", "isv.invalid-app-id", "无效的AppID参数"));
        }
        if (!verify(params)) {
            return respond("error_response", fail("40002", "Invalid Arguments", "isv.invalid-signature", "验签出错"));
        }
        JsonNode biz = read(params.getOrDefault("biz_content", "{}"));
        return switch (method) {
            case "alipay.trade.precreate" -> precreate(root, biz, params);
            case "alipay.trade.page.pay", "alipay.trade.wap.pay" -> cashier(biz, params, request);
            case "alipay.trade.query" -> query(root, biz);
            case "alipay.trade.close" -> close(root, biz);
            case "alipay.trade.refund" -> refund(root, biz);
            case "alipay.data.dataservice.bill.downloadurl.query" -> billUrl(root, biz, request);
            default -> respond("error_response", fail("40004", "Business Failed", "isv.invalid-method", "不存在的方法名"));
        };
    }

    /** The PC / mobile cashier: Alipay renders its own payment page; the sandbox redirects to its cashier. */
    private ResponseEntity<String> cashier(JsonNode biz, Map<String, String> params, HttpServletRequest request) {
        String otn = biz.path("out_trade_no").asText();
        if (ledger.find(Provider.ALIPAY, otn).isEmpty()) {
            String type = "alipay.trade.page.pay".equals(params.get("method")) ? "FAST_INSTANT_TRADE_PAY" : "QUICK_WAP_WAY";
            sandbox.open(Provider.ALIPAY, otn, type, biz.path("subject").asText(),
                    Money.parseYuan(biz.path("total_amount").asText()), params.get("notify_url"),
                    params.get("return_url"), null, expires(biz));
        }
        String base = request.getRequestURL().substring(0, request.getRequestURL().indexOf("/sandbox/"));
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, base + "/sandbox/cashier/ALIPAY/" + otn).build();
    }

    private ResponseEntity<String> precreate(String root, JsonNode biz, Map<String, String> params) {
        String otn = biz.path("out_trade_no").asText();
        long fen;
        try {
            fen = Money.parseYuan(biz.path("total_amount").asText());
        } catch (IllegalArgumentException e) {
            return respond(root, fail("40004", "Business Failed", "ACQ.INVALID_PARAMETER", "total_amount 格式错误"));
        }
        var existing = ledger.find(Provider.ALIPAY, otn);
        if (existing.isPresent() && existing.get().paid()) {
            return respond(root, fail("40004", "Business Failed", "ACQ.TRADE_HAS_SUCCESS", "交易已被支付"));
        }
        if (existing.isEmpty()) {
            sandbox.open(Provider.ALIPAY, otn, "FACE_TO_FACE_PAYMENT", biz.path("subject").asText(), fen,
                    params.get("notify_url"), null, null, expires(biz));
        }
        Map<String, Object> body = ok();
        body.put("out_trade_no", otn);
        body.put("qr_code", "https://qr.alipay.com/bax0" + SandboxService.randomAlnum(20).toLowerCase());
        return respond(root, body);
    }

    private ResponseEntity<String> query(String root, JsonNode biz) {
        String otn = biz.path("out_trade_no").asText();
        var trade = ledger.find(Provider.ALIPAY, otn);
        if (trade.isEmpty()) {
            return respond(root, fail("40004", "Business Failed", "ACQ.TRADE_NOT_EXIST", "交易不存在"));
        }
        SandboxTrade t = trade.get();
        Map<String, Object> body = ok();
        body.put("trade_no", t.tradeNo());
        body.put("out_trade_no", otn);
        body.put("trade_status", switch (t.state()) {
            case SandboxTrade.SUCCESS -> "TRADE_SUCCESS";
            case SandboxTrade.REFUND -> t.refundedFen() >= t.amountFen() ? "TRADE_CLOSED" : "TRADE_SUCCESS";
            case SandboxTrade.CLOSED -> "TRADE_CLOSED";
            default -> "WAIT_BUYER_PAY";
        });
        body.put("total_amount", Money.toYuan(t.amountFen()));
        if (t.paid()) {
            body.put("buyer_logon_id", t.payer());
            body.put("buyer_pay_amount", Money.toYuan(t.amountFen()));
            body.put("receipt_amount", Money.toYuan(t.amountFen()));
            body.put("send_pay_date", t.paidAt().format(ALIPAY_TIME));
        }
        return respond(root, body);
    }

    private ResponseEntity<String> close(String root, JsonNode biz) {
        String otn = biz.path("out_trade_no").asText();
        var trade = ledger.find(Provider.ALIPAY, otn);
        if (trade.isEmpty()) {
            return respond(root, fail("40004", "Business Failed", "ACQ.TRADE_NOT_EXIST", "交易不存在"));
        }
        if (!ledger.close(Provider.ALIPAY, otn)) {
            return respond(root, fail("40004", "Business Failed", "ACQ.REASON_TRADE_STATUS_INVALID", "交易状态不合法"));
        }
        Map<String, Object> body = ok();
        body.put("trade_no", trade.get().tradeNo());
        body.put("out_trade_no", otn);
        return respond(root, body);
    }

    private ResponseEntity<String> refund(String root, JsonNode biz) {
        String otn = biz.path("out_trade_no").asText();
        String requestNo = biz.path("out_request_no").asText(otn);
        var trade = ledger.find(Provider.ALIPAY, otn);
        if (trade.isEmpty()) {
            return respond(root, fail("40004", "Business Failed", "ACQ.TRADE_NOT_EXIST", "交易不存在"));
        }
        if (!trade.get().paid()) {
            return respond(root, fail("40004", "Business Failed", "ACQ.TRADE_STATUS_ERROR", "交易状态不合法"));
        }
        long fen = Money.parseYuan(biz.path("refund_amount").asText());
        boolean repeat = ledger.findRefund(Provider.ALIPAY, requestNo).isPresent();
        if (!repeat) {
            if (fen > trade.get().amountFen() - ledger.refundedOrPending(Provider.ALIPAY, otn)) {
                return respond(root, fail("40004", "Business Failed", "ACQ.REFUND_AMT_NOT_EQUAL_TOTAL", "退款金额超限"));
            }
            LocalDateTime now = sandbox.now();
            ledger.insertRefund(new SandboxRefund(Provider.ALIPAY, requestNo, trade.get().tradeNo(), otn, fen,
                    "SUCCESS", null, now, now));
            ledger.addRefunded(Provider.ALIPAY, otn, fen);
        }
        SandboxTrade t = ledger.find(Provider.ALIPAY, otn).orElseThrow();
        Map<String, Object> body = ok();
        body.put("trade_no", t.tradeNo());
        body.put("out_trade_no", otn);
        body.put("buyer_logon_id", t.payer());
        body.put("fund_change", repeat ? "N" : "Y");
        body.put("refund_fee", Money.toYuan(t.refundedFen()));
        body.put("gmt_refund_pay", sandbox.now().format(ALIPAY_TIME));
        return respond(root, body);
    }

    private ResponseEntity<String> billUrl(String root, JsonNode biz, HttpServletRequest request) {
        LocalDate date = LocalDate.parse(biz.path("bill_date").asText());
        if (!"trade".equals(biz.path("bill_type").asText()) || date.isAfter(sandbox.now().toLocalDate())) {
            return respond(root, fail("40004", "Business Failed", "isp.bill_not_exist", "账单不存在"));
        }
        String base = request.getRequestURL().substring(0, request.getRequestURL().indexOf("/sandbox/"));
        Map<String, Object> body = ok();
        body.put("bill_download_url", base + "/sandbox/alipay/bills/" + date + ".zip");
        return respond(root, body);
    }

    /** Like Alipay's dwbillcenter links: a short-lived URL that needs no signature. */
    @GetMapping(value = "/bills/{date}.zip", produces = "application/zip")
    public ResponseEntity<byte[]> bill(@org.springframework.web.bind.annotation.PathVariable String date) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + date + ".zip\"")
                .body(bills.alipayTradeBillZip(LocalDate.parse(date)));
    }

    // ------------------------------------------------------------- signing

    private boolean verify(Map<String, String> params) {
        String sign = params.get("sign");
        if (sign == null) {
            return false;
        }
        try {
            String content = AlipaySignature.getSignCheckContentV2(new TreeMap<>(params));
            return AlipaySignature.rsa256CheckContent(content, sign,
                    Keys.base64(keys.sandbox().alipayAppPublicKey()), params.getOrDefault("charset", "UTF-8"));
        } catch (AlipayApiException e) {
            return false;
        }
    }

    private ResponseEntity<String> respond(String root, Map<String, Object> body) {
        String inner = json(body);
        String sign;
        try {
            sign = AlipaySignature.rsa256Sign(inner, Keys.base64(keys.sandbox().alipayPrivateKey()), "UTF-8");
        } catch (AlipayApiException e) {
            throw new IllegalStateException(e);
        }
        String full = "{\"" + root + "\":" + inner + ",\"sign\":\"" + sign + "\"}";
        return ResponseEntity.ok().contentType(new MediaType("application", "json", java.nio.charset.StandardCharsets.UTF_8))
                .body(full);
    }

    private static Map<String, Object> ok() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "10000");
        body.put("msg", "Success");
        return body;
    }

    private static Map<String, Object> fail(String code, String msg, String subCode, String subMsg) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("msg", msg);
        body.put("sub_code", subCode);
        body.put("sub_msg", subMsg);
        return body;
    }

    private LocalDateTime expires(JsonNode biz) {
        String text = biz.path("time_expire").asText("");
        return text.isEmpty() ? sandbox.now().plusHours(2) : LocalDateTime.parse(text, ALIPAY_TIME);
    }

    private JsonNode read(String json) {
        try {
            return mapper.readTree(json);
        } catch (JsonProcessingException e) {
            return mapper.createObjectNode();
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
