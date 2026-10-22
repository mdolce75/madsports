package com.madsports.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.madsports.stats.ReportData;
import com.madsports.stats.StatsEngine;
import com.madsports.stats.StatsEngineTest;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Contra un servidor local que imita la API generateContent de Gemini. */
class GeminiAnalystTest {

    static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    /** Análisis que "devuelve Gemini": incluye un jugador que no existe en los datos. */
    static final String ANALYSIS = """
            {"resumen":"Vélez A tuvo un gran Clausura.","fortalezas":["Ataque: 8,89 goles por partido."],
             "debilidades":[],"jugadores_destacados":[{"nombre":"Simona Emma Demata Bianchi","motivo":"Goleadora."},
             {"nombre":"Jugadora Inventada","motivo":"No existe."}],"previa_proximo_rival":"","tendencia":"G G G G G"}
            """;

    HttpServer server;
    final Deque<Integer> statuses = new ArrayDeque<>();
    final List<String> requests = new java.util.concurrent.CopyOnWriteArrayList<>();
    final List<String> apiKeys = new java.util.concurrent.CopyOnWriteArrayList<>();
    final List<String> paths = new java.util.concurrent.CopyOnWriteArrayList<>();
    ReportData data;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1beta/", ex -> {
            paths.add(ex.getRequestURI().getPath());
            apiKeys.add(ex.getRequestHeaders().getFirst("x-goog-api-key"));
            requests.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            int status = statuses.isEmpty() ? 200 : statuses.poll();
            String body = status == 200
                    ? MAPPER.writeValueAsString(Map.of(
                            "candidates", List.of(Map.of("finishReason", "STOP", "content", Map.of("parts", List.of(
                                    Map.of("text", "pensando…", "thought", true),
                                    Map.of("text", ANALYSIS))))),
                            "usageMetadata", Map.of("promptTokenCount", 9000, "candidatesTokenCount", 600)))
                    : "{\"error\":{\"code\":" + status + "}}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        server.start();
        data = StatsEngine.build(StatsEngineTest.VELEZ, "Infantil Femenino", "Clausura",
                List.of(StatsEngineTest.data(765)), StatsEngineTest.TODAY);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    GeminiAnalyst analyst(String apiKey) {
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1beta";
        return new GeminiAnalyst(new AiProperties(true, apiKey, "gemini-3.8-flash", base, Duration.ofSeconds(10)),
                MAPPER, Duration.ofMillis(10));
    }

    @Test
    void buildsASingleRequestWithDataAndSchema() throws Exception {
        AnalysisResult result = analyst("clave-de-prueba").analyze(data).orElseThrow();

        assertThat(paths).containsExactly("/v1beta/models/gemini-3.8-flash:generateContent");
        assertThat(apiKeys).containsExactly("clave-de-prueba");
        JsonNode req = MAPPER.readTree(requests.getFirst());
        assertThat(req.at("/generationConfig/responseMimeType").asText()).isEqualTo("application/json");
        assertThat(req.at("/generationConfig/responseSchema/required").toString())
                .contains("resumen", "jugadores_destacados", "tendencia", "analisis_faltas");
        assertThat(req.at("/systemInstruction/parts/0/text").asText()).contains("RinkMetrics", "no las mezcles");
        String userText = req.at("/contents/0/parts/0/text").asText();
        assertThat(userText).contains("VELEZ (CAVS)", "\"league_context\"", "\"team_detail\"", "\"time_profile\"",
                "Simona Emma Demata Bianchi");

        assertThat(result.source()).isEqualTo("Gemini (gemini-3.8-flash)");
        assertThat(result.summary()).isEqualTo("Vélez A tuvo un gran Clausura.");
        // El texto de "pensamiento" no se mezcla con la respuesta, y el jugador inventado se descarta.
        assertThat(result.keyPlayers()).extracting(AnalysisResult.KeyPlayer::name)
                .containsExactly("Simona Emma Demata Bianchi");
    }

    @Test
    void onFreePlanRateLimitRetriesOnce() {
        statuses.add(429);
        assertThat(analyst("k").analyze(data)).isPresent();
        assertThat(requests).hasSize(2);
    }

    @Test
    void ifItFailsAgainRuleAnalysisIsUsed() {
        statuses.add(429);
        statuses.add(429);
        assertThat(analyst("k").analyze(data)).isEmpty();

        statuses.add(500);
        assertThat(analyst("k").analyze(data)).isEmpty();
    }

    @Test
    void withoutApiKeyItDoesNotCallTheApi() {
        GeminiAnalyst withoutPassword = analyst("");
        assertThat(withoutPassword.available()).isFalse();
        assertThat(withoutPassword.analyze(data)).isEmpty();
        assertThat(requests).isEmpty();
    }
}
