package com.fppstats.analysis;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicException;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fppstats.config.FppProperties;
import com.fppstats.stats.ReportData;
import com.fppstats.stats.ReportData.PlayerLine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Agente de análisis: Claude consulta las cifras con herramientas de solo lectura y termina llamando a
 * {@code submit_analysis}. Las cifras las calcula {@code StatsEngine}; el modelo solo interpreta y redacta.
 * Ante cualquier falla (sin API key, error de red, rechazo, respuesta inválida) devuelve {@link Optional#empty()}
 * y el llamador usa el análisis por reglas.
 */
@Component
public class ClaudeAnalyst {

    private static final Logger log = LoggerFactory.getLogger(ClaudeAnalyst.class);

    private static final String SYSTEM = """
            Sos un analista de hockey sobre patines de Mad Sports. Vas a escribir el análisis de un informe
            estadístico de un club en una categoría de la Federación Porteña de Patín (FPP), Argentina.

            Reglas:
            - Usá las herramientas para leer los datos. Empezá por get_overview y get_league_context, y pedí el
              detalle (resultados, jugadores, pelota parada y disciplina, rivales, próximos partidos, perfil por
              minuto) que necesites.
            - time_profile tiene goles a favor/en contra y tarjetas propias/de los rivales por tramos de 5 minutos,
              solo de los partidos con acta publicada (matchesWithSheet de matchesPlayed): aclaralo si no son todos.
              Amarilla, azul y roja son tarjetas distintas: no las mezcles.
            - Toda cifra que menciones tiene que salir textualmente de lo que devolvieron las herramientas.
              No calcules ni estimes números nuevos; si un dato no está, no lo menciones.
            - Compará siempre contra el contexto de la liga (promedios) para decir si algo es fortaleza o debilidad.
            - Con pocos partidos jugados, decilo y sé prudente con las conclusiones.
            - En categorías formativas (Infantil, Prejuvenil) el tono es formativo y respetuoso:
              son menores, nada de críticas personales a jugadores.
            - Escribí en español rioplatense, claro y concreto, sin relleno.
            - Si el club tiene más de un equipo en la categoría (p. ej. CAVS A y CAVS B), distinguilos.
            - Cuando termines, llamá a submit_analysis exactamente una vez. No escribas el análisis fuera de esa herramienta.
            """;

    private final FppProperties.Ai props;
    private final ObjectMapper mapper;
    private final AnthropicClient client;

    public ClaudeAnalyst(FppProperties props, ObjectMapper mapper) {
        this.props = props.ai();
        this.mapper = mapper;
        this.client = this.props.usable()
                ? AnthropicOkHttpClient.builder().apiKey(this.props.apiKey()).build()
                : null;
    }

    public boolean available() {
        return client != null;
    }

    public Optional<AnalysisResult> analyze(ReportData data) {
        if (client == null) {
            return Optional.empty();
        }
        Usage usage = new Usage();
        try {
            return Optional.ofNullable(runLoop(data, usage));
        } catch (AnthropicException e) {
            log.warn("Falló la llamada a Claude, se usa el análisis por reglas: {}", e.getMessage());
            return Optional.empty();
        } finally {
            log.info("Consumo de Claude para {} {}: {}", data.club().code(), data.category(), usage);
        }
    }

    /** Tokens acumulados de todas las vueltas del loop, para medir el costo real por informe. */
    private static final class Usage {
        private int requests;
        private long input;
        private long cacheWrite;
        private long cacheRead;
        private long output;

        void add(Message response) {
            requests++;
            input += response.usage().inputTokens();
            cacheWrite += response.usage().cacheCreationInputTokens().orElse(0L);
            cacheRead += response.usage().cacheReadInputTokens().orElse(0L);
            output += response.usage().outputTokens();
        }

        @Override
        public String toString() {
            return requests + " pedidos, entrada " + input + " (+" + cacheWrite + " escritos en cache, "
                    + cacheRead + " leídos de cache), salida " + output;
        }
    }

    private AnalysisResult runLoop(ReportData data, Usage usage) {
        AnalysisTools tools = new AnalysisTools(data, mapper);
        List<MessageParam> messages = new ArrayList<>();
        messages.add(MessageParam.builder().role(MessageParam.Role.USER)
                .content("Analizá el informe de " + data.club().name() + " (" + data.club().code() + "), categoría "
                        + data.category() + ", " + data.torneo() + ".")
                .build());
        boolean nudged = false;

        for (int turn = 0; turn < props.maxTurns(); turn++) {
            Message response = client.messages().create(params(messages));
            usage.add(response);
            String stop = response.stopReason().map(Object::toString).orElse("");
            if ("refusal".equals(stop)) {
                log.warn("Claude rechazó el pedido de análisis");
                return null;
            }
            // Se reenvía la respuesta completa (incluidos los bloques de thinking) para no romper el historial.
            messages.add(response.toParam());

            List<ToolUseBlock> calls = response.content().stream()
                    .flatMap(b -> b.toolUse().stream()).toList();
            if (calls.isEmpty()) {
                if (nudged) {
                    return null;
                }
                nudged = true;
                messages.add(MessageParam.builder().role(MessageParam.Role.USER)
                        .content("Entregá el análisis llamando a submit_analysis.").build());
                continue;
            }

            List<ContentBlockParam> results = new ArrayList<>();
            for (ToolUseBlock call : calls) {
                JsonNode input = call._input().convert(JsonNode.class);
                if (AnalysisTools.SUBMIT.equals(call.name())) {
                    AnalysisResult result = validate(input, data);
                    if (result != null) {
                        return result;
                    }
                    results.add(toolResult(call, "Análisis incompleto: faltan resumen, fortalezas o debilidades.", true));
                    continue;
                }
                try {
                    results.add(toolResult(call, tools.run(call.name(), input), false));
                } catch (Exception e) {
                    results.add(toolResult(call, e.getMessage(), true));
                }
            }
            messages.add(MessageParam.builder().role(MessageParam.Role.USER).contentOfBlockParams(results).build());
        }
        log.warn("El agente no entregó el análisis en {} vueltas", props.maxTurns());
        return null;
    }

    private MessageCreateParams params(List<MessageParam> messages) {
        return MessageCreateParams.builder()
                .model(props.model())
                .maxTokens(16000L)
                .system(SYSTEM)
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.of(props.effort())).build())
                // Prefijo estable (tools + system + historial): se cachea entre vueltas del loop.
                .cacheControl(CacheControlEphemeral.builder().build())
                // Si un clasificador rechaza el pedido, la API reintenta con otro modelo automáticamente.
                .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                .tools(toolDefinitions().stream().map(com.anthropic.models.messages.ToolUnion::ofTool).toList())
                .messages(messages)
                .build();
    }

    private static ContentBlockParam toolResult(ToolUseBlock call, String content, boolean error) {
        return ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                .toolUseId(call.id()).content(content).isError(error).build());
    }

    /** Valida la entrega y descarta jugadores que no existen en los datos. */
    private AnalysisResult validate(JsonNode in, ReportData data) {
        String summary = in.path("resumen").asText("").trim();
        List<String> strengths = strings(in.path("fortalezas"));
        List<String> weaknesses = strings(in.path("debilidades"));
        if (summary.isEmpty() || (strengths.isEmpty() && weaknesses.isEmpty())) {
            return null;
        }
        List<String> knownPlayers = data.teams().stream().flatMap(t -> t.players().stream())
                .map(PlayerLine::name).map(String::toLowerCase).toList();
        List<AnalysisResult.KeyPlayer> players = new ArrayList<>();
        for (JsonNode p : in.path("jugadores_destacados")) {
            String name = p.path("nombre").asText("").trim();
            if (knownPlayers.contains(name.toLowerCase())) {
                players.add(new AnalysisResult.KeyPlayer(name, p.path("motivo").asText("").trim()));
            } else {
                log.info("Se descarta jugador destacado inexistente en los datos: {}", name);
            }
        }
        return new AnalysisResult("Claude (" + props.model() + ")", summary, strengths, weaknesses, players,
                in.path("previa_proximo_rival").asText("").trim(), in.path("tendencia").asText("").trim());
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> {
            String s = n.asText("").trim();
            if (!s.isEmpty()) {
                out.add(s);
            }
        });
        return out;
    }

    private static List<Tool> toolDefinitions() {
        Map<String, Object> str = Map.of("type", "string");
        Map<String, Object> strArray = Map.of("type", "array", "items", str);
        return List.of(
                tool(AnalysisTools.OVERVIEW,
                        "Resumen del club en la categoría: equipos, récord total/local/visitante, posiciones, "
                                + "últimos resultados, rendimiento por torneo, mayor victoria y peor derrota.",
                        Map.of(), List.of()),
                tool(AnalysisTools.LEAGUE,
                        "Promedios de la liga (todas las competiciones del informe): goles por equipo y partido, "
                                + "goles a favor del top 3, % de faltas directas y penales convertidos, tarjetas por partido.",
                        Map.of(), List.of()),
                tool(AnalysisTools.TEAM_DETAIL,
                        "Detalle de un equipo del club. section: results (partidos jugados), upcoming (próximos), "
                                + "players (estadísticas por jugador), set_pieces_discipline, rivals (cara a cara), "
                                + "time_profile (goles y tarjetas amarillas/azules/rojas por tramos de 5', propios y de rivales).",
                        Map.of("team_code", Map.of("type", "string", "description", "Código del equipo, p. ej. CAVS o CAVS B"),
                                "section", Map.of("type", "string", "enum", AnalysisTools.SECTIONS)),
                        List.of("team_code", "section")),
                tool(AnalysisTools.SUBMIT,
                        "Entrega el análisis final. Llamar una sola vez, al terminar.",
                        Map.of("resumen", Map.of("type", "string", "description", "3 a 5 oraciones con el panorama general"),
                                "fortalezas", strArray,
                                "debilidades", strArray,
                                "jugadores_destacados", Map.of("type", "array", "items", Map.of(
                                        "type", "object",
                                        "properties", Map.of("nombre", Map.of("type", "string",
                                                        "description", "Nombre exacto como aparece en players"),
                                                "motivo", str),
                                        "required", List.of("nombre", "motivo"),
                                        "additionalProperties", false)),
                                "previa_proximo_rival", Map.of("type", "string",
                                        "description", "Previa del próximo partido; vacío si no hay"),
                                "tendencia", str),
                        List.of("resumen", "fortalezas", "debilidades", "jugadores_destacados",
                                "previa_proximo_rival", "tendencia")));
    }

    private static Tool tool(String name, String description, Map<String, Object> properties, List<String> required) {
        Tool.InputSchema.Properties.Builder props = Tool.InputSchema.Properties.builder();
        properties.forEach((k, v) -> props.putAdditionalProperty(k, JsonValue.from(v)));
        return Tool.builder()
                .name(name)
                .description(description)
                .inputSchema(Tool.InputSchema.builder()
                        .properties(props.build())
                        .required(required)
                        .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                        .build())
                .strict(true)
                .build();
    }
}
