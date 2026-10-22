package com.madsports.monitor;

import com.madsports.account.MailContent;
import com.madsports.account.Mailer;
import com.madsports.catalog.CatalogService;
import com.madsports.sidgad.SidgadClient;
import com.madsports.sidgad.model.Competition;
import com.madsports.sidgad.model.Federation;
import com.madsports.sidgad.model.Models.Match;
import com.madsports.sidgad.parse.SidgadParser;
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
    private static final int COMPETITIONS_TO_TRY = 6;

    /** Resultado de un chequeo: {@code detail} cuenta qué se leyó o, si no está {@code ok}, qué falló. */
    public record Outcome(Instant when, boolean ok, String detail) {
    }

    private final SidgadClient client;
    private final CatalogService catalog;
    private final Mailer mailer;
    private final Clock clock;
    private final String alerts;
    private final String baseUrl;
    private volatile Outcome last;

    public SidgadMonitor(SidgadClient client, CatalogService catalog, Mailer mailer, Clock clock,
                         @Value("${madsports.alerts.email:}") String alerts,
                         @Value("${madsports.mail.base-url}") String baseUrl) {
        this.client = client;
        this.catalog = catalog;
        this.mailer = mailer;
        this.clock = clock;
        this.alerts = alerts == null ? "" : alerts.trim();
        this.baseUrl = baseUrl;
    }

    @Scheduled(cron = "${madsports.monitor.cron:0 0 8 * * *}", zone = "America/Argentina/Buenos_Aires")
    public void daily() {
        check();
    }

    /** Corre el chequeo, guarda el resultado y avisa si falló o si se recuperó de una falla. */
    public Outcome check() {
        Outcome previous = last;
        Outcome r;
        try {
            r = new Outcome(clock.instant(), true, review());
        } catch (RuntimeException e) {
            r = new Outcome(clock.instant(), false, e.getMessage() == null ? e.toString() : e.getMessage());
        }
        last = r;
        if (r.ok()) {
            log.info("Chequeo de SIDGAD: OK ({})", r.detail());
            if (previous != null && !previous.ok()) {
                notify(r, "SIDGAD volvió a responder bien", "El chequeo diario de SIDGAD volvió a andar.");
            }
        } else {
            log.error("Chequeo de SIDGAD: FALLA ({})", r.detail());
            notify(r, "Falla en la lectura de SIDGAD", "El chequeo diario de SIDGAD falló: los informes pueden salir "
                    + "vacíos o con errores hasta que se corrija.");
        }
        return r;
    }

    public Optional<Outcome> last() {
        return Optional.ofNullable(last);
    }

    /** Recorre el camino de un informe; tira {@link IllegalStateException} con el paso que falló. */
    String review() {
        Map<Integer, Integer> seasons = SidgadParser.seasons(client.portal());
        int year = catalog.years().stream().filter(seasons::containsKey).max(Comparator.naturalOrder())
                .orElseThrow(() -> new IllegalStateException("El portal no trae temporadas de los años ofrecidos "
                        + catalog.years() + " (leídas: " + seasons.keySet() + ")."));
        List<Competition> competitions = catalog.competitions(Federation.FPP, year);
        if (competitions.isEmpty()) {
            throw new IllegalStateException("El catálogo no trae competiciones de la FPP para " + year + ".");
        }
        int withCalendar = 0;
        for (Competition c : competitions.subList(0, Math.min(COMPETITIONS_TO_TRY, competitions.size()))) {
            List<Match> matches = SidgadParser.calendar(client.calendar(c.id()));
            if (matches.isEmpty()) {
                continue;
            }
            withCalendar++;
            Optional<Match> played = matches.stream()
                    .filter(m -> m.idp() > 0 && m.homeGoals() != null && m.awayGoals() != null).findFirst();
            if (played.isEmpty()) {
                continue;
            }
            int idp = played.get().idp();
            int players = SidgadParser.lineups(client.gameSheet(c.id(), idp)).size();
            if (players == 0) {
                throw new IllegalStateException("El acta del partido " + idp + " (" + c.name() + ") no trae "
                        + "planteles.");
            }
            return year + ": " + competitions.size() + " competiciones FPP; " + c.name() + " con " + matches.size()
                    + " partidos; acta " + idp + " con " + players + " jugadores.";
        }
        if (withCalendar == 0) {
            throw new IllegalStateException("Ningún calendario de las primeras " + COMPETITIONS_TO_TRY
                    + " competiciones FPP " + year + " trae partidos.");
        }
        // Calendarios con fechas pero sin partidos jugados: temporada que recién empieza, no es una falla.
        return year + ": " + competitions.size() + " competiciones FPP; calendarios sin partidos jugados todavía.";
    }

    private void notify(Outcome r, String subject, String summaryOf) {
        if (alerts.isEmpty()) {
            return;
        }
        mailer.send(new MailContent(alerts, "RinkMetrics: " + subject, summaryOf, "Monitoreo", subject, "team",
                List.of(summaryOf, "Detalle: " + r.detail()), "Abrir RinkMetrics", baseUrl,
                "Aviso automático del chequeo diario de SIDGAD (" + r.when() + ")."));
    }

    /** Antes del primer chequeo el estado es UNKNOWN, que no baja el estado general. */
    @Override
    public Health health() {
        Outcome r = last;
        if (r == null) {
            return Health.unknown().withDetail("detail", "Todavía no se chequeó.").build();
        }
        return (r.ok() ? Health.up() : Health.down()).withDetail("when", r.when().toString())
                .withDetail("detail", r.detail()).build();
    }
}
