package com.madsports.psj;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Cómo se lee gestiondepsj.com.ar ({@code madsports.psj.*}).
 *
 * @param baseUrl        raíz de la API pública ({@code https://gestiondepsj.com.ar/api/public/})
 * @param origin         el sitio (los escudos están en {@code uploads/clubs/} de acá)
 * @param minInterval    espera mínima entre el inicio de un pedido y el siguiente
 * @param maxConcurrency pedidos simultáneos como máximo
 * @param dataTtl        vigencia del cache de tablas, fases y partidos
 * @param catalogTtl     vigencia del cache de los torneos de cada federación
 */
@ConfigurationProperties(prefix = "madsports.psj")
public record PsjProperties(@DefaultValue("https://gestiondepsj.com.ar/api/public/") String baseUrl,
                            @DefaultValue("https://gestiondepsj.com.ar/") String origin,
                            @DefaultValue("300ms") Duration minInterval,
                            @DefaultValue("3") int maxConcurrency,
                            @DefaultValue("30m") Duration dataTtl,
                            @DefaultValue("6h") Duration catalogTtl,
                            @DefaultValue("20s") Duration timeout) {
}
