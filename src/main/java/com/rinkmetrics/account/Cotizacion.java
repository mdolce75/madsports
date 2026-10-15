package com.rinkmetrics.account;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * Tipo de cambio del día para mostrar los precios en pesos. Los planes tienen su precio base en dólares; se pasan a
 * pesos con el dólar oficial del Banco Nación (venta) que publica DolarAPI ({@code rinkmetrics.dolar.url}).
 * <p>
 * Se consulta como mucho una vez por hora. Si DolarAPI no responde se sigue con la última cotización conocida y, si
 * nunca respondió, con {@code rinkmetrics.dolar.respaldo}. Con la URL vacía (tests) se usa siempre el respaldo.
 */
@Service
public class Cotizacion {

    private static final Logger log = LoggerFactory.getLogger(Cotizacion.class);
    private static final Duration VIGENCIA = Duration.ofHours(1);
    private static final ZoneId BUENOS_AIRES = ZoneId.of("America/Argentina/Buenos_Aires");
    /** Los precios en pesos se redondean hacia abajo a centenas: $ 22.950 se muestra $ 22.900. */
    private static final int REDONDEO = -2;
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * @param pesosPorDolar cuántos pesos vale un dólar (venta)
     * @param casa          de dónde sale ("Oficial")
     * @param fecha         día de la cotización
     * @param respaldo      no es la del día: DolarAPI no respondió y se usa el valor de respaldo
     */
    public record Valor(BigDecimal pesosPorDolar, String casa, LocalDate fecha, boolean respaldo) {
    }

    private final String url;
    private final BigDecimal respaldo;
    private final Clock clock;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private volatile Valor ultima;
    private volatile Instant consultada = Instant.EPOCH;

    public Cotizacion(@Value("${rinkmetrics.dolar.url:https://dolarapi.com/v1/dolares/oficial}") String url,
                      @Value("${rinkmetrics.dolar.respaldo:1500}") BigDecimal respaldo, Clock clock) {
        this.url = url;
        this.respaldo = respaldo;
        this.clock = clock;
    }

    /** La cotización del día (o la última conocida). */
    public Valor actual() {
        Instant ahora = clock.instant();
        if (ultima != null && Duration.between(consultada, ahora).compareTo(VIGENCIA) < 0) {
            return ultima;
        }
        synchronized (this) {
            if (ultima == null || Duration.between(consultada, ahora).compareTo(VIGENCIA) >= 0) {
                consultada = ahora;
                Valor nueva = url == null || url.isBlank() ? null : consultar();
                if (nueva != null) {
                    ultima = nueva;
                } else if (ultima == null) {
                    ultima = new Valor(respaldo, "Oficial", LocalDate.now(clock.withZone(BUENOS_AIRES)), true);
                }
            }
            return ultima;
        }
    }

    /** Un precio en dólares, en pesos al tipo de cambio del día, redondeado hacia abajo a centenas. */
    public BigDecimal enPesos(BigDecimal dolares) {
        return enPesos(dolares, actual());
    }

    static BigDecimal enPesos(BigDecimal dolares, Valor valor) {
        return dolares.multiply(valor.pesosPorDolar()).setScale(REDONDEO, RoundingMode.DOWN).setScale(0);
    }

    /** {@code {"casa":"oficial","nombre":"Oficial","compra":1480,"venta":1530,"fechaActualizacion":"…Z"}} */
    Valor consultar() {
        try {
            HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8))
                    .header("Accept", "application/json").GET().build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                log.warn("DolarAPI respondió {}; se sigue con la última cotización", res.statusCode());
                return null;
            }
            return leer(res.body(), clock);
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("No se pudo consultar el tipo de cambio: {}", e.getMessage());
            return null;
        }
    }

    static Valor leer(String body, Clock clock) throws java.io.IOException {
        JsonNode n = JSON.readTree(body);
        BigDecimal venta = n.path("venta").decimalValue();
        if (venta.signum() <= 0) {
            throw new java.io.IOException("Cotización sin valor de venta");
        }
        String fecha = n.path("fechaActualizacion").asText("");
        LocalDate dia = fecha.isBlank() ? LocalDate.now(clock.withZone(BUENOS_AIRES))
                : OffsetDateTime.parse(fecha).atZoneSameInstant(BUENOS_AIRES).toLocalDate();
        return new Valor(venta, n.path("nombre").asText("Oficial"), dia, false);
    }
}
