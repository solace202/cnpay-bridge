package dev.cnpay.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClockConfig {

    /** WeChat Pay and Alipay bills, time_expire and gmt_payment are all Beijing time; so is the database. */
    public static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");

    @Bean
    Clock clock() {
        return Clock.system(BEIJING);
    }
}
