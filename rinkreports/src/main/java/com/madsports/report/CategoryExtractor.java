package com.madsports.report;

import com.madsports.catalog.CatalogService;
import com.madsports.catalog.CatalogService.CategoryOption;
import com.madsports.catalog.CatalogService.ClubView;
import com.madsports.report.ReportService.Request;
import com.madsports.report.ReportService.Scope;
import com.madsports.sidgad.model.Competition;
import com.madsports.sidgad.model.Competition.Category;
import com.madsports.sidgad.model.Federation;
import com.madsports.sidgad.model.Models.LineupPlayer;
import com.madsports.sidgad.model.Models.Match;
import com.madsports.sidgad.model.Models.MatchEvent;
import com.madsports.sidgad.model.Models.PlayerStats;
import com.madsports.sidgad.model.Models.SeasonMatch;
import com.madsports.sidgad.model.Models.TeamRef;
import com.madsports.source.GameSheet;
import com.madsports.source.HockeySource;
import com.madsports.source.HockeySources;
import com.madsports.source.SourceException;
import com.madsports.source.Standings;
import com.madsports.stats.ReportData;
import com.madsports.stats.StatsEngine;
import com.madsports.stats.StatsEngine.CompetitionData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * La extracción de un informe de categoría: lee de la fuente de datos de la federación las tablas, calendarios,
 * estadísticas, actas y fichas de temporada de las competiciones del club (en paralelo; el ritmo lo pone la fuente) y se
 * las pasa a StatsEngine.
 */
public final class CategoryExtractor {

    private static final Logger log = LoggerFactory.getLogger(CategoryExtractor.class);

    private final CatalogService catalog;
    private final HockeySources sources;
    private final Clock clock;

    CategoryExtractor(CatalogService catalog, HockeySources sources, Clock clock) {
        this.catalog = catalog;
        this.sources = sources;
        this.clock = clock;
    }

    /** Lee de la fuente lo que hace falta para el informe de una categoría del club y lo calcula. */
    ReportData extract(Request request, ClubView club, Category category, CategoryOption option,
                       Consumer<String> progress) {
        Scope scope = request.scope();
        int year = request.year();
        Federation fed = request.federation();
        HockeySource source = sources.of(fed);
        List<Competition> comps = catalog.competitionsFor(fed, club.id(), category, scope.tournament(), year);
        List<CompetitionData> data = new ArrayList<>();
        List<String> notPublished = new ArrayList<>();
        Map<Competition, List<Match>> clubMatches = new LinkedHashMap<>();
        int clubSheets;
        // Los pedidos a SIDGAD van en paralelo (cada uno tarda ~1,5 s, casi todo latencia del sitio). El límite de
        // pedidos simultáneos y por segundo lo pone la fuente, para no sobrecargar el sitio.
        try (ExecutorService io = Executors.newVirtualThreadPerTaskExecutor()) {
            progress.accept("Leyendo tablas, calendarios y estadísticas");
            Map<Competition, Future<Optional<Standings>>> tables = new LinkedHashMap<>();
            Map<Competition, Future<Optional<List<Match>>>> calendars = new LinkedHashMap<>();
            Map<Competition, Future<Optional<List<PlayerStats>>>> scorers = new LinkedHashMap<>();
            for (Competition c : comps) {
                tables.put(c, io.submit(() -> source.standings(c)));
                calendars.put(c, io.submit(() -> source.calendar(c)));
                scorers.put(c, io.submit(() -> source.playerStats(c)));
            }
            for (Competition c : comps) {
                String tournament = c.tournamentLabel(comps);
                Standings standings = published(await(tables.get(c)), new Standings(List.of(), null),
                        "Tabla de posiciones (" + tournament + ")", notPublished);
                List<Match> matches = published(await(calendars.get(c)), List.of(),
                        "Calendario y resultados (" + tournament + ")", notPublished);
                List<PlayerStats> players = published(await(scorers.get(c)), List.of(),
                        "Estadísticas por jugador (" + tournament + ")", notPublished);
                clubMatches.put(c, sheetsToRead(c, matches, club.id(), scope.lastMatches()));
                data.add(new CompetitionData(c, standings.rows(), standings.date(), matches, players,
                        new HashMap<>(), Set.of(), new HashMap<>()));
            }

            // Fichas: las de los partidos del club (minuto de goles, tarjetas y faltas) y las del resto de la liga
            // (solo para el promedio de faltas por equipo y partido). Quedan en cache 24 h.
            List<Map.Entry<CompetitionData, Match>> sheets = new ArrayList<>();
            for (CompetitionData cd : data) {
                clubMatches.get(cd.competition()).forEach(m -> sheets.add(Map.entry(cd, m)));
            }
            clubSheets = sheets.size();
            for (CompetitionData cd : data) {
                List<Match> own = clubMatches.get(cd.competition());
                cd.matches().stream()
                        .filter(m -> m.played() && m.idp() > 0 && !own.contains(m))
                        .forEach(m -> sheets.add(Map.entry(cd, m)));
            }
            AtomicInteger done = new AtomicInteger();
            progress.accept("Leyendo actas de partido (0/" + sheets.size() + ")");
            int ownSheets = clubSheets;
            List<Future<SheetRead>> reads = new ArrayList<>();
            for (int i = 0; i < sheets.size(); i++) {
                Map.Entry<CompetitionData, Match> e = sheets.get(i);
                boolean own = i < ownSheets;
                reads.add(io.submit(() -> {
                    GameSheet sheet = source.gameSheet(e.getKey().competition(), e.getValue().idp());
                    progress.accept("Leyendo actas de partido (" + done.incrementAndGet() + "/" + sheets.size() + ")");
                    return new SheetRead(sheet.events(), own ? sheet.lineups() : List.of());
                }));
            }
            // Jugadores de cada equipo del club por competición: los de sus planillas y los de la tabla de SIDGAD.
            Map<Competition, Map<Integer, Integer>> teamOf = new LinkedHashMap<>();
            Map<Integer, String> names = new HashMap<>();
            Map<Integer, int[]> roles = new HashMap<>();   // jugador -> {actas como arquero, actas de jugador}
            for (int i = 0; i < sheets.size(); i++) {
                CompetitionData cd = sheets.get(i).getKey();
                Match m = sheets.get(i).getValue();
                SheetRead read = await(reads.get(i));
                cd.sheets().put(m.idp(), read.events());
                Map<Integer, Integer> own = teamOf.computeIfAbsent(cd.competition(), k -> new LinkedHashMap<>());
                for (TeamRef t : cd.competition().teams()) {
                    if (t.clubId() == club.id() && m.involves(t.teamId())) {
                        boolean home = m.homeTeamId() == t.teamId();
                        read.lineups().stream().filter(p -> p.home() == home).forEach(p -> {
                            own.putIfAbsent(p.playerId(), t.teamId());
                            names.putIfAbsent(p.playerId(), p.name());
                            roles.computeIfAbsent(p.playerId(), k -> new int[2])[p.goalkeeper() ? 0 : 1]++;
                        });
                        // El arquero titular del partido ("P" en el cinco inicial).
                        read.lineups().stream().filter(p -> p.home() == home && p.starter() && p.goalkeeper())
                                .findFirst().ifPresent(p -> cd.startingKeepers()
                                        .put(m.idp(), new StatsEngine.Keeper(p.playerId(), p.name())));
                    }
                }
            }
            for (CompetitionData cd : data) {
                Set<Integer> clubTeams = cd.competition().teams().stream()
                        .filter(t -> t.clubId() == club.id()).map(TeamRef::teamId).collect(Collectors.toSet());
                Map<Integer, Integer> own = teamOf.computeIfAbsent(cd.competition(), k -> new LinkedHashMap<>());
                cd.players().stream().filter(p -> clubTeams.contains(p.teamId())).forEach(p -> {
                    own.putIfAbsent(p.playerId(), p.teamId());
                    names.putIfAbsent(p.playerId(), p.name());
                });
            }
            replaceWithSeasonProfiles(progress, io, source, club, data, teamOf, names, notPublished);
            // Arqueros: figuran con "P" en al menos 2 planillas, o en la mayoría de las suyas (con pocas actas).
            Set<Integer> goalkeepers = roles.entrySet().stream()
                    .filter(e -> e.getValue()[0] >= 2 || (e.getValue()[0] > 0 && e.getValue()[0] >= e.getValue()[1]))
                    .map(Map.Entry::getKey).collect(Collectors.toSet());
            for (int i = 0; i < data.size(); i++) {
                CompetitionData cd = data.get(i);
                data.set(i, new CompetitionData(cd.competition(), cd.standings(), cd.standingsDate(), cd.matches(),
                        cd.players(), cd.sheets(), goalkeepers, cd.startingKeepers()));
            }
        }
        // Las actas faltantes del club ya se informan en el PDF ("N actas de M partidos"); acá solo se resume.
        long missingSheets = data.stream()
                .flatMap(cd -> clubMatches.get(cd.competition()).stream().map(m -> cd.sheets().get(m.idp())))
                .filter(List::isEmpty).count();
        if (missingSheets > 0) {
            notPublished.add("Actas de partido: " + missingSheets + " de " + clubSheets);
        }
        // Apertura, Clausura y después las copas ("Apertura + Clausura + Copa Nacional de Clubes").
        String tournaments = comps.stream()
                .sorted(Comparator.comparing(Competition::tournament, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(c -> c.tournamentLabel(comps)).distinct().collect(Collectors.joining(" + "));
        String label = scope == Scope.LAST_5
                ? "Últimos 5 partidos (" + tournaments + ")"
                : "Temporada " + year + " · " + tournaments;
        return StatsEngine.build(club, ReportService.label(category, option), label, data, LocalDate.now(clock), scope.lastMatches(),
                        option.teamKey(club.code()))
                .withNotPublished(notPublished)
                .withFederation(fed)
                .withLogo(Crests.tile(source.clubLogo(logoFile(comps, club.id()))));
    }

    /**
     * Archivo del escudo del club según teams_array: el más usado entre sus equipos (SIDGAD a veces le pone a un
     * equipo el logo de otro club, pero nunca a la mayoría).
     */
    public static String logoFile(List<Competition> comps, int clubId) {
        return comps.stream()
                .flatMap(c -> c.teams().stream())
                .filter(t -> t.clubId() == clubId && !t.logo().isBlank())
                .collect(Collectors.groupingBy(TeamRef::logo, LinkedHashMap::new, Collectors.counting()))
                .entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    /** Espera el resultado de un pedido en paralelo; si falló, relanza el error original (p. ej. SourceException). */
    static <T> T await(Future<T> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrumpido leyendo SIDGAD", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException(e.getCause());
        }
    }

    /**
     * Desde cuándo sirve una ficha de temporada guardada: bajada después del día del último partido del club (su ficha
     * ya lo incluye) o, si el club jugó hoy, en las últimas 2 horas. Sin partidos jugados, de las últimas 24 horas.
     */
    static Instant profilesNotBefore(ClubView club, List<CompetitionData> data, Instant now) {
        LocalDate last = data.stream().flatMap(cd -> {
                    Set<Integer> own = cd.competition().teams().stream().filter(t -> t.clubId() == club.id())
                            .map(TeamRef::teamId).collect(Collectors.toSet());
                    return cd.matches().stream().filter(m -> m.played() && m.date() != null
                            && (own.contains(m.homeTeamId()) || own.contains(m.awayTeamId())));
                })
                .map(Match::date).max(Comparator.naturalOrder()).orElse(null);
        if (last == null) {
            return now.minus(Duration.ofHours(24));
        }
        Instant dayAfter = last.plusDays(1).atStartOfDay(java.time.ZoneId.of("America/Argentina/Buenos_Aires")).toInstant();
        Instant twoHoursAgo = now.minus(Duration.ofHours(2));
        return dayAfter.isBefore(twoHoursAgo) ? dayAfter : twoHoursAgo;
    }

    /** Lo que se usa de un acta: sus eventos y, en los partidos del club, las planillas. */
    private record SheetRead(List<MatchEvent> events, List<LineupPlayer> lineups) {
    }

    /**
     * Las estadísticas por jugador del club salen de la ficha de temporada de cada jugador: la tabla de SIDGAD solo
     * suma los partidos con acta (y a veces no se publica), así que sus goles no llegan a los del equipo. Quien no
     * tenga ficha conserva su fila de la tabla. Los demás equipos de la liga siguen con la tabla de SIDGAD.
     */
    private void replaceWithSeasonProfiles(Consumer<String> progress, ExecutorService io, HockeySource source,
                                           ClubView club,
                                           List<CompetitionData> data,
                                           Map<Competition, Map<Integer, Integer>> teamOf, Map<Integer, String> names,
                                           List<String> notPublished) {
        Map<Integer, Set<Integer>> seasonsOf = new LinkedHashMap<>();
        teamOf.forEach((c, players) -> players.keySet()
                .forEach(id -> seasonsOf.computeIfAbsent(id, k -> new HashSet<>()).add(c.seasonId())));
        if (seasonsOf.isEmpty()) {
            return;
        }
        progress.accept("Leyendo las fichas de temporada de los jugadores del club");
        Instant notBefore = profilesNotBefore(club, data, clock.instant());
        Map<Integer, Future<List<SeasonMatch>>> pending = new LinkedHashMap<>();
        seasonsOf.forEach((id, seasons) -> pending.put(id, io.submit(() -> {
            List<SeasonMatch> rows = new ArrayList<>();
            for (int season : seasons) {
                try {
                    rows.addAll(source.playerSeason(id, season, notBefore));
                } catch (SourceException e) {
                    log.info("Sin ficha de temporada del jugador {}: {}", id, e.getMessage());
                }
            }
            return rows;
        })));
        Map<Integer, List<SeasonMatch>> profiles = new HashMap<>();
        pending.forEach((id, f) -> profiles.put(id, await(f)));
        for (int i = 0; i < data.size(); i++) {
            CompetitionData cd = data.get(i);
            Set<Integer> clubTeams = cd.competition().teams().stream()
                    .filter(t -> t.clubId() == club.id()).map(TeamRef::teamId).collect(Collectors.toSet());
            List<PlayerStats> ours = SeasonScorers.forCompetition(cd.competition(), club.id(),
                    teamOf.getOrDefault(cd.competition(), Map.of()), names, profiles, cd.players());
            if (ours.isEmpty()) {
                continue;
            }
            List<PlayerStats> players = new ArrayList<>(cd.players().stream()
                    .filter(p -> !clubTeams.contains(p.teamId())).toList());
            players.addAll(ours);
            data.set(i, new CompetitionData(cd.competition(), cd.standings(), cd.standingsDate(), cd.matches(),
                    players, cd.sheets(), cd.goalkeepers(), cd.startingKeepers()));
            // Con las fichas de temporada ya no falta: la tabla de SIDGAD de esa competición no hace falta.
            notPublished.remove("Estadísticas por jugador ("
                    + cd.competition().tournamentLabel(data.stream().map(CompetitionData::competition).toList()) + ")");
        }
    }

    /** Si la fuente no publicó el dato, se anota para el PDF y se sigue con {@code empty}. */
    private static <T> T published(Optional<T> value, T empty, String what, List<String> notPublished) {
        if (value.isEmpty()) {
            notPublished.add(what);
        }
        return value.orElse(empty);
    }

    /**
     * Partidos jugados del club con acta para leer. Con "últimos N" alcanza con los últimos N de cada equipo
     * en cada competición: los últimos N de la temporada están siempre dentro de ese conjunto.
     */
    static List<Match> sheetsToRead(Competition c, List<Match> matches, int clubId, int lastMatches) {
        List<Match> result = new ArrayList<>();
        for (TeamRef t : c.teams()) {
            if (t.clubId() != clubId) {
                continue;
            }
            List<Match> played = matches.stream()
                    .filter(m -> m.played() && m.idp() > 0 && m.involves(t.teamId()))
                    .sorted(Comparator.comparing(Match::date, Comparator.nullsLast(Comparator.naturalOrder()))
                            .thenComparing(Match::round))
                    .toList();
            int skip = lastMatches > 0 ? Math.max(0, played.size() - lastMatches) : 0;
            played.subList(skip, played.size()).stream().filter(m -> !result.contains(m)).forEach(result::add);
        }
        return result;
    }
}
