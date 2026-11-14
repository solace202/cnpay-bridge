package dev.cnpay.sandbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cnpay.config.CnpayProperties;
import dev.cnpay.crypto.KeyRing;
import dev.cnpay.crypto.WechatPayCrypto;
import dev.cnpay.provider.Provider;
import dev.cnpay.provider.wechat.WechatPayClients;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Plays api.mch.weixin.qq.com for the official SDK: verifies the merchant's {@code WECHATPAY2-SHA256-RSA2048}
 * Authorization header, answers with signed {@code Wechatpay-*} headers, and sends encrypted callbacks.
 */
@Hidden
@RestController
@RequestMapping(WechatPaySandboxController.PREFIX)
@ConditionalOnProperty(name = "cnpay.mode", havingValue = "sandbox", matchIfMissing = true)
public class WechatPaySandboxController {

    static final String PREFIX = "/sandbox/wechatpay";
    private static final Pattern AUTH_FIELD = Pattern.compile("(\\w+)=\"([^\"]*)\"");

    private final SandboxService sandbox;
    private final SandboxLedger ledger;
    private final SandboxBills bills;
    private final KeyRing keys;
    private final CnpayProperties properties;
    private final ObjectMapper mapper;

    public WechatPaySandboxController(SandboxService sandbox, SandboxLedger ledger, SandboxBills bills, KeyRing keys,
                                      CnpayProperties properties, ObjectMapper mapper) {
        this.sandbox = sandbox;
        this.ledger = ledger;
        this.bills = bills;
        this.keys = keys;
        this.properties = properties;
        this.mapper = mapper;
    }

    @PostMapping("/v3/pay/transactions/{type}")
    public ResponseEntity<String> prepay(@PathVariable String type, @RequestBody String body, HttpServletRequest req) {
        ResponseEntity<String> denied = authenticate(req, body);
        if (denied != null) {
            return denied;
        }
        JsonNode r = read(body);
        String tradeType = switch (type) {
            case "native" -> "NATIVE";
            case "jsapi" -> "JSAPI";
            case "h5" -> "MWEB";
            default -> null;
        };
        if (tradeType == null) {
            return error(HttpStatus.NOT_FOUND, "NOT_FOUND", "Unsupported trade type " + type);
        }
        String otn = r.path("out_trade_no").asText();
        long total = r.path("amount").path("total").asLong(0);
        if (!properties.wechat().mchId().equals(r.path("mchid").asText())
                || !properties.wechat().appId().equals(r.path("appid").asText())) {
            return error(HttpStatus.BAD_REQUEST, "APPID_MCHID_NOT_MATCH", "appid和mch_id不匹配");
        }
        if (otn.length() < 6 || otn.length() > 32 || total <= 0 || r.path("notify_url").asText().isEmpty()) {
            return error(HttpStatus.BAD_REQUEST, "PARAM_ERROR", "参数错误: out_trade_no / amount.total / notify_url");
        }
        if ("JSAPI".equals(tradeType) && r.path("payer").path("openid").asText().isEmpty()) {
            return error(HttpStatus.BAD_REQUEST, "PARAM_ERROR", "JSAPI下单缺少payer.openid");
        }
        var existing = ledger.find(Provider.WECHAT, otn);
        if (existing.isPresent()) {
            if (existing.get().paid()) {
                return error(HttpStatus.BAD_REQUEST, "ORDERPAID", "该订单已支付");
            }
            if (existing.get().amountFen() != total) {
                return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "201 商户订单号重复");
            }
        } else {
            LocalDateTime expires = r.hasNonNull("time_expire")
                    ? OffsetDateTime.parse(r.path("time_expire").asText())
                    .atZoneSameInstant(dev.cnpay.config.ClockConfig.BEIJING).toLocalDateTime()
                    : sandbox.now().plusHours(2);
            String openid = r.path("payer").path("openid").asText(null);
            sandbox.open(Provider.WECHAT, otn, tradeType, r.path("description").asText(), total,
                    r.path("notify_url").asText(), null, openid, expires);
        }
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(otn.getBytes(StandardCharsets.UTF_8));
        Map<String, Object> response = switch (tradeType) {
            case "NATIVE" -> Map.of("code_url", "weixin://wxpay/bizpayurl?pr=" + token);
            case "JSAPI" -> Map.of("prepay_id", "wx" + sandbox.now().format(java.time.format.DateTimeFormatter.ofPattern("ddHHmmss"))
                    + WechatPayCrypto.hex(24).toLowerCase());
            default -> Map.of("h5_url", sandboxBase(req) + "/sandbox/cashier/WECHAT/" + otn);
        };
        return signed(HttpStatus.OK, json(response));
    }

    @GetMapping("/v3/pay/transactions/out-trade-no/{outTradeNo}")
    public ResponseEntity<String> query(@PathVariable String outTradeNo, HttpServletRequest req) {
        ResponseEntity<String> denied = authenticate(req, "");
        if (denied != null) {
            return denied;
        }
        return ledger.find(Provider.WECHAT, outTradeNo)
                .map(t -> signed(HttpStatus.OK, json(sandbox.wechatTransaction(t))))
                .orElseGet(() -> error(HttpStatus.NOT_FOUND, "ORDER_NOT_EXIST", "订单不存在"));
    }

    @PostMapping("/v3/pay/transactions/out-trade-no/{outTradeNo}/close")
    public ResponseEntity<String> close(@PathVariable String outTradeNo, @RequestBody String body, HttpServletRequest req) {
        ResponseEntity<String> denied = authenticate(req, body);
        if (denied != null) {
            return denied;
        }
        var trade = ledger.find(Provider.WECHAT, outTradeNo);
        if (trade.isEmpty()) {
            return error(HttpStatus.NOT_FOUND, "ORDER_NOT_EXIST", "订单不存在");
        }
        if (trade.get().paid()) {
            return error(HttpStatus.BAD_REQUEST, "ORDERPAID", "订单已支付，不能关闭");
        }
        ledger.close(Provider.WECHAT, outTradeNo);
        return signed(HttpStatus.NO_CONTENT, "");
    }

    @PostMapping("/v3/refund/domestic/refunds")
    public ResponseEntity<String> refund(@RequestBody String body, HttpServletRequest req) {
        ResponseEntity<String> denied = authenticate(req, body);
        if (denied != null) {
            return denied;
        }
        JsonNode r = read(body);
        String otn = r.path("out_trade_no").asText();
        String outRefundNo = r.path("out_refund_no").asText();
        long fen = r.path("amount").path("refund").asLong(0);
        var trade = ledger.find(Provider.WECHAT, otn);
        if (trade.isEmpty()) {
            return error(HttpStatus.NOT_FOUND, "RESOURCE_NOT_EXISTS", "订单不存在");
        }
        if (!trade.get().paid()) {
            return error(HttpStatus.FORBIDDEN, "NOT_ENOUGH", "订单未支付");
        }
        var existing = ledger.findRefund(Provider.WECHAT, outRefundNo);
        if (existing.isPresent()) {
            return signed(HttpStatus.OK, json(sandbox.wechatRefund(trade.get(), existing.get())));
        }
        if (r.path("amount").path("total").asLong() != trade.get().amountFen()) {
            return error(HttpStatus.BAD_REQUEST, "PARAM_ERROR", "订单金额与原订单不一致");
        }
        if (fen <= 0 || fen > trade.get().amountFen() - ledger.refundedOrPending(Provider.WECHAT, otn)) {
            return error(HttpStatus.FORBIDDEN, "NOT_ENOUGH", "申请退款金额超过订单可退金额");
        }
        SandboxRefund refund = new SandboxRefund(Provider.WECHAT, outRefundNo, sandbox.refundId(), otn, fen,
                "PROCESSING", r.path("notify_url").asText(null), sandbox.now(), null);
        ledger.insertRefund(refund);
        CompletableFuture.runAsync(() -> sandbox.settleWechatRefund(refund),
                CompletableFuture.delayedExecutor(properties.sandbox().notifyDelay().toMillis(), TimeUnit.MILLISECONDS));
        Map<String, Object> response = new LinkedHashMap<>(sandbox.wechatRefund(trade.get(), refund));
        response.put("status", "PROCESSING");
        response.put("channel", "ORIGINAL");
        response.put("create_time", SandboxService.rfc3339(refund.createdAt()));
        response.remove("refund_status");
        return signed(HttpStatus.OK, json(response));
    }

    @GetMapping("/v3/bill/tradebill")
    public ResponseEntity<String> tradeBill(@RequestParam("bill_date") String billDate,
                                            @RequestParam(value = "bill_type", defaultValue = "ALL") String billType,
                                            HttpServletRequest req) {
        ResponseEntity<String> denied = authenticate(req, "");
        if (denied != null) {
            return denied;
        }
        LocalDate date = LocalDate.parse(billDate);
        if (date.isAfter(sandbox.now().toLocalDate())) {
            return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "账单日期不能晚于今天");
        }
        byte[] csv = bills.wechatTradeBill(date).getBytes(StandardCharsets.UTF_8);
        return signed(HttpStatus.OK, json(Map.of(
                "hash_type", "SHA1",
                "hash_value", sha1(csv),
                "download_url", "https://" + WechatPayClients.API_HOST + "/v3/billdownload/file?token=" + billDate)));
    }

    /** Download is authenticated but the body is the raw bill, not signed JSON. */
    @GetMapping(value = "/v3/billdownload/file", produces = "text/csv;charset=UTF-8")
    public ResponseEntity<byte[]> download(@RequestParam String token, HttpServletRequest req) {
        if (authenticate(req, "") != null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(bills.wechatTradeBill(LocalDate.parse(token)).getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------- signatures

    /** Verifies the merchant request signature exactly as WeChat Pay does. Returns an error response or null. */
    private ResponseEntity<String> authenticate(HttpServletRequest req, String body) {
        String header = req.getHeader("Authorization");
        if (header == null || !header.startsWith(WechatPayCrypto.SCHEMA + " ")) {
            return error(HttpStatus.UNAUTHORIZED, "SIGN_ERROR", "缺少Authorization或认证类型错误");
        }
        Map<String, String> f = new LinkedHashMap<>();
        Matcher m = AUTH_FIELD.matcher(header);
        while (m.find()) {
            f.put(m.group(1), m.group(2));
        }
        if (!properties.wechat().mchId().equals(f.get("mchid"))) {
            return error(HttpStatus.UNAUTHORIZED, "SIGN_ERROR", "mchid不匹配");
        }
        if (!keys.wechatMerchantSerialNumber().equals(f.get("serial_no"))) {
            return error(HttpStatus.UNAUTHORIZED, "SIGN_ERROR", "商户证书序列号不正确");
        }
        long ts = Long.parseLong(f.getOrDefault("timestamp", "0"));
        if (Math.abs(Instant.now().getEpochSecond() - ts) > 300) {
            return error(HttpStatus.UNAUTHORIZED, "SIGN_ERROR", "请求已过期");
        }
        String path = req.getRequestURI().substring(PREFIX.length());
        String canonicalUrl = req.getQueryString() == null ? path : path + "?" + req.getQueryString();
        String message = WechatPayCrypto.message(req.getMethod(), canonicalUrl, f.get("timestamp"), f.get("nonce_str"), body);
        if (!WechatPayCrypto.verify(keys.sandbox().wechatMerchantPublicKey(), message, f.get("signature"))) {
            return error(HttpStatus.UNAUTHORIZED, "SIGN_ERROR", "签名错误");
        }
        return null;
    }

    private ResponseEntity<String> signed(HttpStatus status, String body) {
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String nonce = WechatPayCrypto.nonce(32);
        String signature = WechatPayCrypto.sign(keys.sandbox().wechatPlatformPrivateKey(),
                WechatPayCrypto.message(timestamp, nonce, body));
        var builder = ResponseEntity.status(status)
                .header("Request-ID", UUID.randomUUID().toString())
                .header("Wechatpay-Timestamp", timestamp)
                .header("Wechatpay-Nonce", nonce)
                .header("Wechatpay-Serial", keys.wechatPlatformPublicKeyId())
                .header("Wechatpay-Signature", signature)
                .header("Wechatpay-Signature-Type", WechatPayCrypto.SCHEMA);
        if (body.isEmpty()) {
            return builder.build();
        }
        return builder.contentType(MediaType.APPLICATION_JSON).body(body);
    }

    private ResponseEntity<String> error(HttpStatus status, String code, String message) {
        return signed(status, json(Map.of("code", code, "message", message)));
    }

    private JsonNode read(String body) {
        try {
            return mapper.readTree(body);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Malformed JSON", e);
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sandboxBase(HttpServletRequest req) {
        return req.getRequestURL().substring(0, req.getRequestURL().indexOf(PREFIX));
    }

    private static String sha1(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
