package dev.cnpay.web;

import com.alipay.api.internal.util.AlipaySignature;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.cnpay.config.CnpayProperties;
import dev.cnpay.money.Money;
import dev.cnpay.order.Order;
import dev.cnpay.order.OrderService;
import dev.cnpay.provider.Provider;
import dev.cnpay.provider.alipay.AlipayClients;
import dev.cnpay.sandbox.SandboxService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/** The shopper-facing checkout page: QR / JSAPI parameters / cashier link, live status polling. */
@Controller
public class PayPageController {

    private final OrderService orders;
    private final AlipayClients alipay;
    private final CnpayProperties properties;
    private final ObjectProvider<SandboxService> sandbox;
    private final ObjectMapper pretty;

    public PayPageController(OrderService orders, AlipayClients alipay, CnpayProperties properties,
                             ObjectProvider<SandboxService> sandbox, ObjectMapper mapper) {
        this.orders = orders;
        this.alipay = alipay;
        this.properties = properties;
        this.sandbox = sandbox;
        this.pretty = mapper.copy().enable(SerializationFeature.INDENT_OUTPUT);
    }

    @GetMapping("/pay/{id}")
    public String checkout(@PathVariable String id, @RequestParam Map<String, String> query, Model model)
            throws JsonProcessingException {
        Order order = orders.get(id);
        Map<String, Object> payment = orders.payment(order);
        model.addAttribute("order", order);
        model.addAttribute("amount", Money.toYuan(order.amountFen()));
        model.addAttribute("payment", payment);
        model.addAttribute("sandbox", properties.sandboxMode());
        if ("qr_code".equals(payment.get("kind"))) {
            model.addAttribute("qrSvg", QrCodes.svg((String) payment.get("qr_content")));
        }
        if ("wx_request_payment".equals(payment.get("kind"))) {
            Map<String, Object> params = new LinkedHashMap<>(payment);
            params.remove("kind");
            model.addAttribute("jsapiJson", pretty.writeValueAsString(params));
        }
        // Alipay page/wap pay sends the shopper back with signed sync-return params. They prove nothing about
        // settlement (only the async notification or a query does) but a forged return should still be spotted.
        if (query.containsKey("sign") && query.containsKey("out_trade_no")) {
            boolean ok;
            try {
                ok = AlipaySignature.rsaCheckV1(new TreeMap<>(query), alipay.alipayPublicKeyBase64(), "UTF-8", "RSA2");
            } catch (Exception e) {
                ok = false;
            }
            model.addAttribute("returnVerified", ok);
        }
        return "pay";
    }

    @GetMapping("/pay/{id}/status")
    @ResponseBody
    public Map<String, Object> status(@PathVariable String id) {
        Order order = orders.get(id);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", order.status());
        body.put("paidAt", order.paidAt());
        return body;
    }

    /** Sandbox only: stands in for the shopper's phone scanning the QR and confirming. */
    @PostMapping("/pay/{id}/simulate")
    public Object simulate(@PathVariable String id) {
        SandboxService s = sandbox.getIfAvailable();
        if (s == null) {
            return ResponseEntity.notFound().build();
        }
        Order order = orders.get(id);
        SandboxService.PayResult result = s.pay(order.provider(), order.outTradeNo());
        return "redirect:/pay/" + id + "?simulated=" + result.name().toLowerCase();
    }

}
