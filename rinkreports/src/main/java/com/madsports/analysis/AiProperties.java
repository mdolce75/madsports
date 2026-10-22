package com.madsports.analysis;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Análisis con Google Gemini (plan gratuito de Google AI Studio), {@code rinkmetrics.ai.*}.
 *
 * @param enabled si es false (o no hay API key) se usa el análisis por reglas
 * @param apiKey  API key de Google AI Studio
 * @param model   modelo, p. ej. gemini-3.8-flash
 * @param baseUrl raíz de la API ({@code https://generativelanguage.googleapis.com/v1beta})
 */
@ConfigurationProperties(prefix = "rinkmetrics.ai")
public record AiProperties(boolean enabled, String apiKey, String model, String baseUrl, Duration timeout) {

    public boolean usable() {
        return enabled && apiKey != null && !apiKey.isBlank();
    }
}
