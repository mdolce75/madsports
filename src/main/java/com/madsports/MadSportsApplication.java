package com.madsports;

import com.madsports.config.RinkMetricsProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;
import java.time.ZoneId;

/** Mad Sports: sus productos RinkReports (informes) y RinkMetrics (métricas en vivo), en una sola app. */
@SpringBootApplication
@EnableConfigurationProperties(RinkMetricsProperties.class)
@EnableScheduling
public class MadSportsApplication {

    public static void main(String[] args) {
        SpringApplication.run(MadSportsApplication.class, args);
    }

    @Bean
    Clock clock() {
        return Clock.system(ZoneId.of("America/Argentina/Buenos_Aires"));
    }
}
