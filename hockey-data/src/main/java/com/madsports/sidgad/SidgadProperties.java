package com.madsports.sidgad;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Cómo se lee SIDGAD ({@code madsports.sidgad.*}).
 *
 * @param baseUrl         raíz de los fragmentos ({@code https://www.server2.sidgad.es/cap/})
 * @param origin          portal que SIDGAD exige como Origin/Referer
 * @param minInterval     espera mínima entre el inicio de un pedido al sitio y el siguiente
 * @param maxConcurrency  pedidos simultáneos al sitio como máximo
 * @param dataTtl         vigencia en memoria de tablas, calendarios y estadísticas (delante de las copias guardadas)
 * @param catalogTtl      vigencia de las copias guardadas de SIDGAD: catálogo, tablas, calendarios, estadísticas y
 *                        fichas de temporada (en memoria y en la base)
 * @param years           años que se ofrecen (p. ej. 2026, 2027)
 * @param clubFactor      el informe del club completo lee las actas de todas sus categorías: usa este múltiplo de
 *                        pedidos simultáneos y de pedidos por segundo
 * @param seasons         año → id de temporada de SIDGAD, de respaldo: el portal dejó de exponer el selector de
 *                        temporadas (lo migró a su API con token), así que el mapeo se configura acá. Si el portal
 *                        vuelve a traerlo, el selector tiene prioridad sobre esto.
 */
@ConfigurationProperties(prefix = "madsports.sidgad")
public record SidgadProperties(String baseUrl, String origin, Duration minInterval, int maxConcurrency,
                               Duration dataTtl, Duration catalogTtl, Duration timeout, List<Integer> years,
                               @DefaultValue("5") int clubFactor, Map<Integer, Integer> seasons) {

    @ConstructorBinding
    public SidgadProperties {
        seasons = seasons == null ? Map.of() : Map.copyOf(seasons);
    }

    /** Compatibilidad (tests): sin el mapa de temporadas, que queda vacío. */
    public SidgadProperties(String baseUrl, String origin, Duration minInterval, int maxConcurrency,
                            Duration dataTtl, Duration catalogTtl, Duration timeout, List<Integer> years,
                            int clubFactor) {
        this(baseUrl, origin, minInterval, maxConcurrency, dataTtl, catalogTtl, timeout, years, clubFactor, Map.of());
    }
}
