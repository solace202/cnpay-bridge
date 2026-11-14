package dev.cnpay.web;

import dev.cnpay.config.CnpayProperties;
import dev.cnpay.order.CreateOrderRequest;
import dev.cnpay.order.OrderService;
import dev.cnpay.order.OrderView;
import dev.cnpay.provider.Channel;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** A one-product storefront for trying the flows in sandbox mode. Disabled in live mode. */
@Controller
public class DemoController {

    private final OrderService orders;
    private final CnpayProperties properties;

    public DemoController(OrderService orders, CnpayProperties properties) {
        this.orders = orders;
        this.properties = properties;
    }

    @GetMapping("/")
    public String home(Model model) {
        if (!properties.sandboxMode()) {
            return "redirect:/swagger-ui.html";
        }
        model.addAttribute("channels", Channel.values());
        model.addAttribute("sandbox", true);
        return "demo";
    }

    @PostMapping("/demo/checkout")
    public String checkout(@RequestParam Channel channel, @RequestParam(defaultValue = "88.00") String amount,
                           HttpServletRequest request) {
        if (!properties.sandboxMode()) {
            return "redirect:/";
        }
        String merchantOrderNo = "DEMO-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("MMddHHmmss"))
                + ThreadLocalRandom.current().nextInt(100, 999);
        OrderView order = orders.create(new CreateOrderRequest(merchantOrderNo, channel, amount,
                "Matcha starter kit 抹茶入门套装", channel == Channel.WECHAT_JSAPI ? "oUpF8uMuAJO_M2pxb1Q9zNjWeS6o" : null,
                null, 30), request.getRemoteAddr());
        return "redirect:/pay/" + order.id();
    }
}
