package com.rinkmetrics;

import com.rinkmetrics.config.FppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.time.Clock;
import java.time.ZoneId;

@SpringBootApplication
@EnableConfigurationProperties(FppProperties.class)
public class RinkMetricsApplication {

    public static void main(String[] args) {
        SpringApplication.run(RinkMetricsApplication.class, args);
    }

    @Bean
    Clock clock() {
        return Clock.system(ZoneId.of("America/Argentina/Buenos_Aires"));
    }
}
