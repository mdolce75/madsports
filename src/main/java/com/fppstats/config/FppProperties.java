package com.fppstats.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

@ConfigurationProperties(prefix = "fpp")
public record FppProperties(Sidgad sidgad, Ai ai) {

    /**
     * @param baseUrl         raíz de los fragmentos ({@code https://www.server2.sidgad.es/cap/})
     * @param origin          portal que SIDGAD exige como Origin/Referer
     * @param federationId    federación a incluir (3 = FPP)
     * @param minInterval     espera mínima entre pedidos al sitio
     * @param dataTtl         vigencia del cache de clasificaciones/calendario/estadísticas
     * @param catalogTtl      vigencia del cache del catálogo de competiciones
     * @param levels          categorías relevadas, en orden (de Infantil para arriba)
     */
    public record Sidgad(String baseUrl, String origin, int federationId, Duration minInterval,
                         Duration dataTtl, Duration catalogTtl, Duration timeout, List<String> levels) {
    }

    /**
     * @param enabled  si es false (o no hay API key) se usa el análisis por reglas
     * @param model    modelo de Claude
     * @param effort   low | medium | high | xhigh | max
     * @param maxTurns tope de vueltas del loop de herramientas
     */
    public record Ai(boolean enabled, String apiKey, String model, String effort, int maxTurns) {

        public boolean usable() {
            return enabled && apiKey != null && !apiKey.isBlank();
        }
    }
}
