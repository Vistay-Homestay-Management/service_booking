package com.vistay.booking.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TimeConfiguration {
    @Bean
    Clock systemClock() {
        return Clock.systemUTC();
    }
}
