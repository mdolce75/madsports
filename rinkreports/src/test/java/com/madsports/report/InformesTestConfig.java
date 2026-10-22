package com.madsports.report;

import com.madsports.analysis.RuleBasedAnalyst;
import com.madsports.pdf.Plantillas;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.thymeleaf.ThymeleafAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.time.Clock;
import java.time.ZoneId;

/**
 * Lo que necesitan los tests de los informes de RinkReports, sin levantar la aplicación completa (sin base ni
 * seguridad): las plantillas (Thymeleaf), el PDF, el Excel, el análisis por reglas y los informes de ejemplo.
 */
@Configuration
@Import({SampleReport.class, PdfRenderer.class, ExcelExporter.class, RuleBasedAnalyst.class, Plantillas.class})
@ImportAutoConfiguration({ThymeleafAutoConfiguration.class, JacksonAutoConfiguration.class})
class InformesTestConfig {

    @Bean
    Clock clock() {
        return Clock.system(ZoneId.of("America/Argentina/Buenos_Aires"));
    }
}
