package dev.cnpay;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class CnpayApplication {

    public static void main(String[] args) {
        SpringApplication.run(CnpayApplication.class, args);
    }
}
