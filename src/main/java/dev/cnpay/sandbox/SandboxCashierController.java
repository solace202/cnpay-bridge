package dev.cnpay.sandbox;

import dev.cnpay.config.CnpayProperties;
import dev.cnpay.money.Money;
import dev.cnpay.provider.Provider;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

/** The pages WeChat Pay H5 and the Alipay cashier would show the shopper. */
@Controller
@ConditionalOnProperty(name = "cnpay.mode", havingValue = "sandbox", matchIfMissing = true)
public class SandboxCashierController {

    private final SandboxService sandbox;
    private final SandboxNotifier notifier;
    private final CnpayProperties properties;

    public SandboxCashierController(SandboxService sandbox, SandboxNotifier notifier, CnpayProperties properties) {
        this.sandbox = sandbox;
        this.notifier = notifier;
        this.properties = properties;
    }

    @GetMapping("/sandbox/cashier/{provider}/{outTradeNo}")
    public String cashier(@PathVariable Provider provider, @PathVariable String outTradeNo, Model model) {
        SandboxTrade trade = sandbox.find(provider, outTradeNo).orElse(null);
        model.addAttribute("trade", trade);
        model.addAttribute("provider", provider);
        model.addAttribute("amount", trade == null ? null : Money.toYuan(trade.amountFen()));
        return "cashier";
    }

    @PostMapping("/sandbox/cashier/{provider}/{outTradeNo}")
    public String pay(@PathVariable Provider provider, @PathVariable String outTradeNo, Model model) {
        SandboxService.PayResult result = sandbox.pay(provider, outTradeNo);
        SandboxTrade trade = sandbox.find(provider, outTradeNo).orElse(null);
        if (trade != null && trade.returnUrl() != null && result != SandboxService.PayResult.CLOSED) {
            return "redirect:" + trade.returnUrl() + (trade.returnUrl().contains("?") ? "&" : "?") + syncReturn(trade);
        }
        model.addAttribute("trade", trade);
        model.addAttribute("provider", provider);
        model.addAttribute("amount", trade == null ? null : Money.toYuan(trade.amountFen()));
        model.addAttribute("result", result);
        return "cashier";
    }

    /** Alipay's synchronous return (return_url) parameters, signed like the real thing. */
    private String syncReturn(SandboxTrade t) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("charset", "UTF-8");
        p.put("out_trade_no", t.outTradeNo());
        p.put("method", "FAST_INSTANT_TRADE_PAY".equals(t.tradeType()) ? "alipay.trade.page.pay.return" : "alipay.trade.wap.pay.return");
        p.put("total_amount", Money.toYuan(t.amountFen()));
        p.put("trade_no", t.tradeNo());
        p.put("auth_app_id", properties.alipay().appId());
        p.put("version", "1.0");
        p.put("app_id", properties.alipay().appId());
        p.put("seller_id", "2088000000000001");
        p.put("timestamp", sandbox.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        return notifier.signAlipay(p).entrySet().stream()
                .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }
}
