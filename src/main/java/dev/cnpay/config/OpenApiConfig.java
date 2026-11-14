package dev.cnpay.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI openApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("CnPay Bridge API")
                        .version("0.1.0")
                        .description("""
                                One order API for WeChat Pay (API v3) and Alipay: Native QR, JSAPI / mini-program, H5, \
                                Alipay QR / PC page / mobile wap. Amounts are CNY strings with two decimals ("88.00"); \
                                POST endpoints accept an `Idempotency-Key` header.""")
                        .license(new License().name("MIT")))
                .components(new Components().addSecuritySchemes("basic",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("basic")))
                .addSecurityItem(new SecurityRequirement().addList("basic"));
    }
}
