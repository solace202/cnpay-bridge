package dev.cnpay.web;

import dev.cnpay.provider.ProviderException;
import dev.cnpay.sandbox.SandboxCashierController;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

@ControllerAdvice(assignableTypes = {PayPageController.class, AdminController.class, DemoController.class,
        SandboxCashierController.class})
public class PageExceptionHandler {

    @ExceptionHandler(ApiException.class)
    String api(ApiException e, Model model, HttpServletResponse response) {
        response.setStatus(e.status().value());
        model.addAttribute("message", e.getMessage());
        return "error-page";
    }

    @ExceptionHandler({ProviderException.class, IllegalArgumentException.class})
    String provider(RuntimeException e, Model model, HttpServletResponse response) {
        response.setStatus(e instanceof ProviderException ? 502 : 400);
        model.addAttribute("message", e.getMessage());
        return "error-page";
    }
}
