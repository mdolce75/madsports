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
     * @param minInterval     espera mínima entre el inicio de un pedido al sitio y el siguiente
     * @param maxConcurrency  pedidos simultáneos al sitio como máximo
     * @param dataTtl         vigencia del cache de clasificaciones/calendario/estadísticas
     * @param catalogTtl      vigencia del cache del catálogo de competiciones
     * @param levels          categorías relevadas, en orden (de Infantil para arriba)
     * @param years           años que se ofrecen (p. ej. 2026, 2027)
     */
    public record Sidgad(String baseUrl, String origin, int federationId, Duration minInterval, int maxConcurrency,
                         Duration dataTtl, Duration catalogTtl, Duration timeout, List<String> levels,
                         List<Integer> years) {
    }

    /**
     * Análisis con Google Gemini (plan gratuito de Google AI Studio).
     *
     * @param enabled si es false (o no hay API key) se usa el análisis por reglas
     * @param apiKey  API key de Google AI Studio
     * @param model   modelo, p. ej. gemini-3.8-flash
     * @param baseUrl raíz de la API ({@code https://generativelanguage.googleapis.com/v1beta})
     */
    public record Ai(boolean enabled, String apiKey, String model, String baseUrl, Duration timeout) {

        public boolean usable() {
            return enabled && apiKey != null && !apiKey.isBlank();
        }
    }
}
