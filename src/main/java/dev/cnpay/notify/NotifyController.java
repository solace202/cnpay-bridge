package dev.cnpay.notify;

import io.swagger.v3.oas.annotations.Hidden;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Public callback endpoints. Authentication is the providers' signatures, not HTTP auth. */
@Hidden
@RestController
@RequestMapping("/notify")
public class NotifyController {

    private final NotificationService notifications;

    public NotifyController(NotificationService notifications) {
        this.notifications = notifications;
    }

    /** WeChat Pay expects 200/204 on success and retries (15s, 15s, 30s, 3m, ... up to 24h) on anything else. */
    @PostMapping(value = "/wechat", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, String>> wechat(
            @RequestHeader(value = "Wechatpay-Serial", required = false) String serial,
            @RequestHeader(value = "Wechatpay-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "Wechatpay-Nonce", required = false) String nonce,
            @RequestHeader(value = "Wechatpay-Signature", required = false) String signature,
            @RequestHeader(value = "Wechatpay-Signature-Type", required = false, defaultValue = "WECHATPAY2-SHA256-RSA2048") String signType,
            @RequestBody String body) {
        NotifyResponse response = notifications.handleWechat(serial, timestamp, nonce, signature, signType, body);
        if (response.acknowledged()) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("code", "FAIL", "message", response.message()));
    }

    /** Alipay expects the literal body "success"; anything else is retried (4m, 10m, 10m, 1h, 2h, 6h, 15h). */
    @PostMapping(value = "/alipay", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.TEXT_PLAIN_VALUE)
    public String alipay(@RequestParam Map<String, String> params) {
        return notifications.handleAlipay(params).acknowledged() ? "success" : "failure";
    }
}
