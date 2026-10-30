package com.madsports.psj;

import com.madsports.psj.PsjParser.Phase;
import com.madsports.psj.PsjParser.Sheet;
import com.madsports.psj.PsjParser.Tournament;
import com.madsports.sidgad.model.Competition;
import com.madsports.sidgad.model.Competition.Category;
import com.madsports.sidgad.model.Federation;
import com.madsports.sidgad.model.Models.Match;
import com.madsports.sidgad.model.Models.PlayerStats;
import com.madsports.sidgad.model.Models.SeasonMatch;
import com.madsports.sidgad.model.Models.StandingRow;
import com.madsports.sidgad.model.Models.TeamRef;
import com.madsports.source.DataSource;
import com.madsports.source.GameSheet;
import com.madsports.source.HockeySource;
import com.madsports.source.Standings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * gestiondepsj.com.ar como fuente de datos (San Juan y Entre Ríos): la API pública de {@link PsjClient} pasada a los
 * modelos con {@link PsjParser}. El id de temporada de una competición es su año (la API no tiene temporadas: el año
 * sale del nombre del torneo o, si no lo dice, de la fecha de su primer partido).
 */
@Component
public class PsjSource implements HockeySource {

    private static final Logger log = LoggerFactory.getLogger(PsjSource.class);

    /** Duración de cada tiempo en San Juan y Entre Ríos (la API no la informa). */
    static final int PERIOD_MINUTES = 25;

    private final PsjClient client;

    public PsjSource(PsjClient client) {
        this.client = client;
    }

    @Override
    public DataSource kind() {
        return DataSource.PSJ;
    }

    /** El comité de la Copa Nacional de Clubes (CNTHsP). */
    static final int CUP_COMMITTEE = 1;

    /**
     * Los torneos de la federación ({@code federation_id} = {@link Federation#id()}) en el año, con sus clubes, y la
     * Copa Nacional de Clubes (del comité CNTHsP) en la categoría que diga la federación ({@link Federation#cupCategory()}).
     */
    @Override
    public List<Competition> competitions(Federation f, int year) {
        return competitions(f.id(), f.cupCategory(), year);
    }

    List<Competition> competitions(int federationId, Category cupCategory, int year) {
        List<Tournament> own = PsjParser.tournaments(client.tournaments(federationId));
        List<Tournament> cups = cupCategory == null ? List.of()
                : PsjParser.tournaments(client.committeeTournaments(CUP_COMMITTEE)).stream()
                .filter(t -> PsjParser.norm(t.name()).startsWith("COPA NACIONAL")).toList();
        List<Competition> out = new ArrayList<>();
        try (ExecutorService io = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Competition>> pending = new ArrayList<>();
            own.forEach(t -> pending.add(io.submit(() -> competition(t, federationId, null))));
            cups.forEach(t -> pending.add(io.submit(() -> competition(t, federationId, cupCategory))));
            pending.forEach(c -> out.add(await(c)));
        }
        return out.stream().filter(Objects::nonNull).filter(c -> c.seasonId() == year)
                .sorted(Comparator.comparingInt(Competition::id)).toList();
    }

    /** El torneo como competición (una copa, con {@code cup}); null si no tiene año (ni en el nombre ni en sus partidos). */
    private Competition competition(Tournament t, int federationId, Category cup) {
        List<TeamRef> teams = PsjParser.teams(client.standings(t.id()));
        Integer year = PsjParser.year(t.name()).stream().boxed().findFirst().orElseGet(() -> firstYear(t.id(), teams));
        if (year == null) {
            return null;
        }
        return cup == null ? Competition.of(t.id(), t.name(), federationId, year, teams, PERIOD_MINUTES)
                : Competition.cup(t.id(), t.name(), federationId, year, cup, teams, PERIOD_MINUTES);
    }

    /** El año del primer partido con fecha del torneo. */
    private Integer firstYear(int tournamentId, List<TeamRef> teams) {
        for (Phase p : PsjParser.phases(client.phases(tournamentId))) {
            Optional<LocalDate> first = PsjParser.matches(client.phaseMatches(p.id()), p.order(), teams).stream()
                    .map(Match::date).filter(Objects::nonNull).min(Comparator.naturalOrder());
            if (first.isPresent()) {
                return first.get().getYear();
            }
        }
        return null;
    }

    @Override
    public Optional<Standings> standings(Competition c) {
        List<StandingRow> rows = PsjParser.standings(client.standings(c.id()));
        return rows.isEmpty() ? Optional.empty() : Optional.of(new Standings(rows, null));
    }

    /** Los partidos de todas las fechas del torneo, en su orden. */
    @Override
    public Optional<List<Match>> calendar(Competition c) {
        List<Phase> phases = PsjParser.phases(client.phases(c.id()));
        List<Match> matches = new ArrayList<>();
        try (ExecutorService io = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<List<Match>>> pending = phases.stream()
                    .map(p -> io.submit(() -> PsjParser.matches(client.phaseMatches(p.id()), p.order(), c.teams())))
                    .toList();
            pending.forEach(f -> matches.addAll(await(f)));
        }
        return matches.isEmpty() ? Optional.empty() : Optional.of(matches);
    }

    /** No hay una tabla de goleadores completa: sale de las planillas y los eventos de los partidos terminados. */
    @Override
    public Optional<List<PlayerStats>> playerStats(Competition c) {
        List<Match> played = calendar(c).orElse(List.of()).stream().filter(Match::played).toList();
        List<Sheet> sheets = new ArrayList<>();
        try (ExecutorService io = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Sheet>> pending = played.stream()
                    .map(m -> io.submit(() -> PsjParser.sheet(client.match(m.idp())))).toList();
            pending.forEach(f -> sheets.add(await(f)));
        }
        List<PlayerStats> stats = PsjParser.playerStats(sheets);
        return stats.isEmpty() ? Optional.empty() : Optional.of(stats);
    }

    @Override
    public GameSheet gameSheet(Competition c, int matchId) {
        Sheet sheet = PsjParser.sheet(client.match(matchId));
        if (!sheet.unmatched().isEmpty()) {
            log.info("Partido {} de gestiondepsj: {} evento(s) sin jugador en la planilla: {}", matchId,
                    sheet.unmatched().size(), sheet.unmatched());
        }
        return GameSheet.of(sheet.events(), sheet.lineups());
    }

    /** La ficha del jugador desde gestiondepsj llega con el informe de jugador (#159): por ahora, sin ficha. */
    @Override
    public List<SeasonMatch> playerSeason(int playerId, int seasonId) {
        return List.of();
    }

    @Override
    public List<SeasonMatch> playerSeason(int playerId, int seasonId, Instant notBefore) {
        return List.of();
    }

    @Override
    public byte[] clubLogo(String file) {
        return client.clubLogo(file);
    }

    private static <T> T await(Future<T> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PsjClient.PsjException("Interrumpido leyendo gestiondepsj", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException(e.getCause());
        }
    }
}
