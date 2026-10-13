package com.fppstats.report;

import com.fppstats.analysis.AnalysisResult;
import com.fppstats.analysis.RuleBasedAnalyst;
import com.fppstats.stats.ReportData;
import com.fppstats.stats.StatsEngine;
import com.fppstats.stats.StatsEngineTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "fpp.ai.enabled=false")
class PdfRendererTest {

    @Autowired
    PdfRenderer renderer;

    @Autowired
    RuleBasedAnalyst rules;

    @Test
    void generaElPdfConLasSeccionesDelInforme() throws Exception {
        ReportData data = StatsEngine.build(StatsEngineTest.VELEZ, "Infantil Femenino", "Clausura",
                List.of(StatsEngineTest.data(765)), StatsEngineTest.HOY);
        AnalysisResult analysis = rules.analyze(data);

        String html = renderer.html(data, analysis);
        assertThat(html).contains("VELEZ (CAVS)", "VELEZ A (CAVS A)", "VELEZ B (CAVS B)",
                "Goleadores y asistidores", "Pelota parada", "Cara a cara", "Análisis: Reglas");

        byte[] pdf = renderer.pdf(data, analysis);
        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
        // Copia para revisar a ojo: target/informe-ejemplo.pdf
        Files.write(Path.of("target", "informe-ejemplo.pdf"), pdf);
    }
}
