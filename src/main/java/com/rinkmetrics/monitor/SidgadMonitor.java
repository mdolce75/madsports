package com.rinkmetrics.monitor;

import com.rinkmetrics.account.MailContent;
import com.rinkmetrics.account.Mailer;
import com.rinkmetrics.catalog.CatalogService;
import com.rinkmetrics.sidgad.SidgadClient;
import com.rinkmetrics.sidgad.model.Competition;
import com.rinkmetrics.sidgad.model.Federacion;
import com.rinkmetrics.sidgad.model.Models.Match;
import com.rinkmetrics.sidgad.parse.SidgadParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Chequeo diario de SIDGAD: los informes dependen de leer su HTML, y si el sitio cambia de formato los parsers devuelven
 * listas vacías sin dar error. Una vez por día ({@code rinkmetrics.monitor.cron}) se recorre el camino de un informe con
 * datos reales (temporada del portal, catálogo de la FPP, calendario con partidos jugados, acta con sus planteles) y el
 * resultado queda en el health check ({@code sidgad}). Si falla, o si se recupera, avisa por mail a
 * {@code ALERTAS_EMAIL}.
 */
@Component("sidgad")
public class SidgadMonitor implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(SidgadMonitor.class);
    /** Competiciones que se recorren buscando una con partidos jugados (las primeras fechas pueden no tener). */
    private static final int COMPETICIONES_A_PROBAR = 6;

    /** Resultado de un chequeo: {@code detalle} cuenta qué se leyó o, si no está {@code ok}, qué falló. */
    public record Resultado(Instant cuando, boolean ok, String detalle) {
    }

    private final SidgadClient client;
    private final CatalogService catalog;
    private final Mailer mailer;
    private final Clock clock;
    private final String alertas;
    private final String baseUrl;
    private volatile Resultado ultimo;

    public SidgadMonitor(SidgadClient client, CatalogService catalog, Mailer mailer, Clock clock,
                         @Value("${rinkmetrics.alertas.email:}") String alertas,
                         @Value("${rinkmetrics.mail.base-url}") String baseUrl) {
        this.client = client;
        this.catalog = catalog;
        this.mailer = mailer;
        this.clock = clock;
        this.alertas = alertas == null ? "" : alertas.trim();
        this.baseUrl = baseUrl;
    }

    @Scheduled(cron = "${rinkmetrics.monitor.cron:0 0 8 * * *}", zone = "America/Argentina/Buenos_Aires")
    public void diario() {
        chequear();
    }

    /** Corre el chequeo, guarda el resultado y avisa si falló o si se recuperó de una falla. */
    public Resultado chequear() {
        Resultado anterior = ultimo;
        Resultado r;
        try {
            r = new Resultado(clock.instant(), true, revisar());
        } catch (RuntimeException e) {
            r = new Resultado(clock.instant(), false, e.getMessage() == null ? e.toString() : e.getMessage());
        }
        ultimo = r;
        if (r.ok()) {
            log.info("Chequeo de SIDGAD: OK ({})", r.detalle());
            if (anterior != null && !anterior.ok()) {
                avisar(r, "SIDGAD volvió a responder bien", "El chequeo diario de SIDGAD volvió a andar.");
            }
        } else {
            log.error("Chequeo de SIDGAD: FALLA ({})", r.detalle());
            avisar(r, "Falla en la lectura de SIDGAD", "El chequeo diario de SIDGAD falló: los informes pueden salir "
                    + "vacíos o con errores hasta que se corrija.");
        }
        return r;
    }

    public Optional<Resultado> ultimo() {
        return Optional.ofNullable(ultimo);
    }

    /** Recorre el camino de un informe; tira {@link IllegalStateException} con el paso que falló. */
    String revisar() {
        Map<Integer, Integer> temporadas = SidgadParser.seasons(client.portal());
        int anio = catalog.years().stream().filter(temporadas::containsKey).max(Comparator.naturalOrder())
                .orElseThrow(() -> new IllegalStateException("El portal no trae temporadas de los años ofrecidos "
                        + catalog.years() + " (leídas: " + temporadas.keySet() + ")."));
        List<Competition> competiciones = catalog.competitions(Federacion.FPP, anio);
        if (competiciones.isEmpty()) {
            throw new IllegalStateException("El catálogo no trae competiciones de la FPP para " + anio + ".");
        }
        int conCalendario = 0;
        for (Competition c : competiciones.subList(0, Math.min(COMPETICIONES_A_PROBAR, competiciones.size()))) {
            List<Match> partidos = SidgadParser.calendar(client.calendar(c.id()));
            if (partidos.isEmpty()) {
                continue;
            }
            conCalendario++;
            Optional<Match> jugado = partidos.stream()
                    .filter(m -> m.idp() > 0 && m.homeGoals() != null && m.awayGoals() != null).findFirst();
            if (jugado.isEmpty()) {
                continue;
            }
            int idp = jugado.get().idp();
            int jugadores = SidgadParser.lineups(client.gameSheet(c.id(), idp)).size();
            if (jugadores == 0) {
                throw new IllegalStateException("El acta del partido " + idp + " (" + c.name() + ") no trae "
                        + "planteles.");
            }
            return anio + ": " + competiciones.size() + " competiciones FPP; " + c.name() + " con " + partidos.size()
                    + " partidos; acta " + idp + " con " + jugadores + " jugadores.";
        }
        if (conCalendario == 0) {
            throw new IllegalStateException("Ningún calendario de las primeras " + COMPETICIONES_A_PROBAR
                    + " competiciones FPP " + anio + " trae partidos.");
        }
        // Calendarios con fechas pero sin partidos jugados: temporada que recién empieza, no es una falla.
        return anio + ": " + competiciones.size() + " competiciones FPP; calendarios sin partidos jugados todavía.";
    }

    private void avisar(Resultado r, String asunto, String resumen) {
        if (alertas.isEmpty()) {
            return;
        }
        mailer.send(new MailContent(alertas, "RinkMetrics: " + asunto, resumen, "Monitoreo", asunto, "equipo",
                List.of(resumen, "Detalle: " + r.detalle()), "Abrir RinkMetrics", baseUrl,
                "Aviso automático del chequeo diario de SIDGAD (" + r.cuando() + ")."));
    }

    /** Antes del primer chequeo el estado es UNKNOWN, que no baja el estado general. */
    @Override
    public Health health() {
        Resultado r = ultimo;
        if (r == null) {
            return Health.unknown().withDetail("detalle", "Todavía no se chequeó.").build();
        }
        return (r.ok() ? Health.up() : Health.down()).withDetail("cuando", r.cuando().toString())
                .withDetail("detalle", r.detalle()).build();
    }
}
