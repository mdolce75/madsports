package com.fppstats.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fppstats.config.FppProperties;
import com.fppstats.stats.ReportData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Análisis con Google Gemini (plan gratuito de Google AI Studio), en un solo pedido: todos los datos ya
 * calculados van en el mensaje y la respuesta es JSON con esquema fijo. Un solo pedido por informe respeta
 * el límite de pedidos por minuto del plan gratuito. Gemini no calcula cifras: solo redacta.
 * Ante cualquier falla devuelve vacío y se usa el análisis por reglas.
 */
@Component
public class GeminiAnalyst {

    private static final Logger log = LoggerFactory.getLogger(GeminiAnalyst.class);

    /** Espera ante un 429 (límite del plan gratuito) antes del único reintento, si la API no indica otra. */
    static final Duration DEFAULT_RETRY_WAIT = Duration.ofSeconds(20);
    private static final Duration MAX_RETRY_WAIT = Duration.ofSeconds(60);

    static final String SYSTEM = """
            Sos un analista de hockey sobre patines de Mad Sports. Vas a escribir el análisis de un informe
            estadístico de un club en una categoría de la Federación Porteña de Patín (FPP), Argentina.

            Los datos ya calculados del informe están en el mensaje, en JSON: overview, league_context y, por
            equipo, team_detail (results, upcoming, players, set_pieces_discipline, rivals, time_profile).

            Reglas:
            - time_profile tiene goles a favor/en contra y tarjetas propias/de los rivales por tramos de 5 minutos,
              solo de los partidos con acta publicada (matchesWithSheet de matchesPlayed): aclaralo si no son todos.
              Amarilla, azul y roja son tarjetas distintas: no las mezcles. También trae los minutos por partido
              con uno menos y con uno más por sanciones (azul 2', roja 4'): citá esos valores, no los recalcules.
            - Toda cifra que menciones tiene que salir textualmente de los datos que recibiste.
              No calcules ni estimes números nuevos; si un dato no está, no lo menciones.
            - Compará siempre contra el contexto de la liga (promedios) para decir si algo es fortaleza o debilidad.
            - Con pocos partidos jugados, decilo y sé prudente con las conclusiones.
            - En categorías formativas (Infantil, Prejuvenil) el tono es formativo y respetuoso:
              son menores, nada de críticas personales a jugadores.
            - Escribí en español rioplatense, claro y concreto, sin relleno.
            - Si el club tiene más de un equipo en la categoría (p. ej. CAVS A y CAVS B), distinguilos.
            - En jugadores_destacados usá el nombre exacto como figura en players.
            - Respondé solo con el JSON pedido (resumen, fortalezas, debilidades, jugadores_destacados,
              previa_proximo_rival, tendencia). previa_proximo_rival vacío si no hay partidos programados.
            """;

    private final FppProperties.Ai props;
    private final ObjectMapper mapper;
    private final HttpClient http;
    private final Duration retryWait;

    @Autowired
    public GeminiAnalyst(FppProperties props, ObjectMapper mapper) {
        this(props, mapper, DEFAULT_RETRY_WAIT);
    }

    GeminiAnalyst(FppProperties props, ObjectMapper mapper, Duration retryWait) {
        this.props = props.ai();
        this.mapper = mapper;
        this.retryWait = retryWait;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    }

    /** True si la IA está habilitada y hay API key. */
    public boolean available() {
        return props.usable();
    }

    public Optional<AnalysisResult> analyze(ReportData data) {
        if (!available()) {
            return Optional.empty();
        }
        try {
            String body = mapper.writeValueAsString(request(data));
            HttpResponse<String> res = send(body);
            if (res.statusCode() == 429) {
                Duration wait = retryAfter(res);
                log.info("Gemini: límite del plan gratuito (429), reintento en {} s", wait.toSeconds());
                Thread.sleep(wait);
                res = send(body);
            }
            if (res.statusCode() != 200) {
                log.warn("Gemini respondió {}: {}", res.statusCode(), abbreviate(res.body()));
                return Optional.empty();
            }
            JsonNode json = mapper.readTree(res.body());
            log.info("Consumo de Gemini para {} {}: {}", data.club().code(), data.category(), json.path("usageMetadata"));
            JsonNode candidate = json.path("candidates").path(0);
            String finish = candidate.path("finishReason").asText("");
            if (!finish.isEmpty() && !"STOP".equals(finish)) {
                log.warn("Gemini terminó con finishReason={}", finish);
                return Optional.empty();
            }
            StringBuilder text = new StringBuilder();
            for (JsonNode part : candidate.path("content").path("parts")) {
                if (!part.path("thought").asBoolean(false)) {
                    text.append(part.path("text").asText(""));
                }
            }
            JsonNode analysis = mapper.readTree(text.toString());
            return Optional.ofNullable(AnalysisValidator.validate(analysis, data, "Gemini (" + props.model() + ")"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Falló el análisis con Gemini, se usa el análisis por reglas: {}", e.toString());
            return Optional.empty();
        }
    }

    private HttpResponse<String> send(String body) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(props.baseUrl() + "/models/" + props.model() + ":generateContent"))
                .timeout(props.timeout())
                .header("Content-Type", "application/json")
                .header("x-goog-api-key", props.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    Map<String, Object> request(ReportData data) throws Exception {
        String context = new AnalysisContext(data, mapper).fullContext();
        String ask = "Analizá el informe de " + data.club().name() + " (" + data.club().code() + "), categoría "
                + data.category() + ", " + data.torneo() + ".";
        return Map.of(
                "systemInstruction", Map.of("parts", List.of(Map.of("text", SYSTEM))),
                "contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text",
                        ask + "\n\nDatos del informe (JSON):\n" + context)))),
                "generationConfig", Map.of(
                        "responseMimeType", "application/json",
                        "responseSchema", schema(),
                        "temperature", 0.4));
    }

    /** Esquema de la respuesta (subconjunto OpenAPI que usa Gemini). */
    private static Map<String, Object> schema() {
        Map<String, Object> str = Map.of("type", "STRING");
        Map<String, Object> strArray = Map.of("type", "ARRAY", "items", str);
        return Map.of(
                "type", "OBJECT",
                "properties", Map.of(
                        "resumen", str,
                        "fortalezas", strArray,
                        "debilidades", strArray,
                        "jugadores_destacados", Map.of("type", "ARRAY", "items", Map.of(
                                "type", "OBJECT",
                                "properties", Map.of("nombre", str, "motivo", str),
                                "required", List.of("nombre", "motivo"))),
                        "previa_proximo_rival", str,
                        "tendencia", str),
                "required", List.of("resumen", "fortalezas", "debilidades", "jugadores_destacados",
                        "previa_proximo_rival", "tendencia"));
    }

    private Duration retryAfter(HttpResponse<String> res) {
        return res.headers().firstValue("Retry-After")
                .map(v -> {
                    try {
                        return Duration.ofSeconds(Long.parseLong(v.trim()));
                    } catch (NumberFormatException e) {
                        return retryWait;
                    }
                })
                .map(d -> d.compareTo(MAX_RETRY_WAIT) > 0 ? MAX_RETRY_WAIT : d)
                .orElse(retryWait);
    }

    private static String abbreviate(String s) {
        return s == null ? "" : s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
