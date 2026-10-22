package com.madsports.account;

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
 * pesos con el dólar oficial del Banco Nación (venta) que publica DolarAPI ({@code madsports.dollar.url}).
 * <p>
 * Se consulta como mucho una vez por hora. Si DolarAPI no responde se sigue con la última cotización conocida y, si
 * nunca respondió, con {@code madsports.dollar.fallback}. Con la URL vacía (tests) se usa siempre el respaldo.
 */
@Service
public class ExchangeRate {

    private static final Logger log = LoggerFactory.getLogger(ExchangeRate.class);
    private static final Duration VALIDITY = Duration.ofHours(1);
    private static final ZoneId BUENOS_AIRES = ZoneId.of("America/Argentina/Buenos_Aires");
    /** Los precios en pesos se redondean hacia abajo a centenas: $ 22.950 se muestra $ 22.900. */
    private static final int ROUNDING = -2;
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * @param pesosPorDolar cuántos pesos vale un dólar (venta)
     * @param casa          de dónde sale ("Oficial")
     * @param fecha         día de la cotización
     * @param respaldo      no es la del día: DolarAPI no respondió y se usa el valor de respaldo
     */
    public record Quote(BigDecimal pesosPerDollar, String house, LocalDate date, boolean fallback) {
    }

    private final String url;
    private final BigDecimal fallback;
    private final Clock clock;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private volatile Quote last;
    private volatile Instant fetchedAt = Instant.EPOCH;

    public ExchangeRate(@Value("${madsports.dollar.url:https://dolarapi.com/v1/dollars/oficial}") String url,
                      @Value("${madsports.dollar.fallback:1500}") BigDecimal fallback, Clock clock) {
        this.url = url;
        this.fallback = fallback;
        this.clock = clock;
    }

    /** La cotización del día (o la última conocida). */
    public Quote current() {
        Instant now = clock.instant();
        if (last != null && Duration.between(fetchedAt, now).compareTo(VALIDITY) < 0) {
            return last;
        }
        synchronized (this) {
            if (last == null || Duration.between(fetchedAt, now).compareTo(VALIDITY) >= 0) {
                fetchedAt = now;
                Quote fresh = url == null || url.isBlank() ? null : fetch();
                if (fresh != null) {
                    last = fresh;
                } else if (last == null) {
                    last = new Quote(fallback, "Oficial", LocalDate.now(clock.withZone(BUENOS_AIRES)), true);
                }
            }
            return last;
        }
    }

    /** Un precio en dólares, en pesos al tipo de cambio del día, redondeado hacia abajo a centenas. */
    public BigDecimal inPesos(BigDecimal dollars) {
        return inPesos(dollars, current());
    }

    static BigDecimal inPesos(BigDecimal dollars, Quote value) {
        return dollars.multiply(value.pesosPerDollar()).setScale(ROUNDING, RoundingMode.DOWN).setScale(0);
    }

    /** {@code {"house":"oficial","name":"Oficial","compra":1480,"sale":1530,"fechaActualizacion":"…Z"}} */
    Quote fetch() {
        try {
            HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8))
                    .header("Accept", "application/json").GET().build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                log.warn("DolarAPI respondió {}; se sigue con la última cotización", res.statusCode());
                return null;
            }
            return read(res.body(), clock);
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("No se pudo consultar el tipo de cambio: {}", e.getMessage());
            return null;
        }
    }

    static Quote read(String body, Clock clock) throws java.io.IOException {
        JsonNode n = JSON.readTree(body);
        BigDecimal sale = n.path("venta").decimalValue();
        if (sale.signum() <= 0) {
            throw new java.io.IOException("Cotización sin valor de venta");
        }
        String date = n.path("fechaActualizacion").asText("");
        LocalDate day = date.isBlank() ? LocalDate.now(clock.withZone(BUENOS_AIRES))
                : OffsetDateTime.parse(date).atZoneSameInstant(BUENOS_AIRES).toLocalDate();
        return new Quote(sale, n.path("nombre").asText("Oficial"), day, false);
    }
}
