package com.madsports.sidgad;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * Cómo se lee SIDGAD ({@code rinkmetrics.sidgad.*}).
 *
 * @param baseUrl         raíz de los fragmentos ({@code https://www.server2.sidgad.es/cap/})
 * @param origin          portal que SIDGAD exige como Origin/Referer
 * @param minInterval     espera mínima entre el inicio de un pedido al sitio y el siguiente
 * @param maxConcurrency  pedidos simultáneos al sitio como máximo
 * @param dataTtl         vigencia del cache de clasificaciones/calendario/estadísticas
 * @param catalogTtl      vigencia del cache del catálogo de competiciones
 * @param years           años que se ofrecen (p. ej. 2026, 2027)
 */
@ConfigurationProperties(prefix = "madsports.sidgad")
public record SidgadProperties(String baseUrl, String origin, Duration minInterval, int maxConcurrency,
                               Duration dataTtl, Duration catalogTtl, Duration timeout, List<Integer> years) {
}
