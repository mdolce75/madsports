package com.madsports.compare;

import com.madsports.catalog.CatalogService.ClubView;
import com.madsports.compare.Comparison.CardLine;
import com.madsports.compare.Comparison.HeadToHead;
import com.madsports.compare.Comparison.Scorer;
import com.madsports.compare.Comparison.Side;
import com.madsports.compare.Comparison.View;
import com.madsports.report.ReportService.Scope;
import com.madsports.sidgad.model.Competition;
import com.madsports.sidgad.model.Federation;
import com.madsports.sidgad.model.Models.EventType;
import com.madsports.sidgad.model.Models.Match;
import com.madsports.sidgad.model.Models.MatchEvent;
import com.madsports.sidgad.model.Models.TeamRef;
import com.madsports.stats.ReportData;
import com.madsports.stats.ReportData.PlayerLine;
import com.madsports.stats.ReportData.TeamReport;
import com.madsports.stats.StatsEngine;
import com.madsports.stats.StatsEngine.CompetitionData;

import java.text.Normalizer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Arma el {@link Comparison} con lo leído de la fuente para cada equipo (sus competiciones del año en la categoría).
 * Los números de cada equipo salen del mismo cálculo que su informe ({@link StatsEngine}); acá se agregan las tarjetas
 * una por una con el marcador del momento, los enfrentamientos y el resumen.
 */
public final class ComparisonCalc {

    private ComparisonCalc() {
    }

    /** Un equipo a comparar: el club, la clave del equipo ("CAVS B"; null si es el único) y lo leído de la fuente. */
    public record Input(ClubView club, String teamKey, List<CompetitionData> data, String logo) {
    }

    /** El comparativo del año completo. */
    public static Comparison build(Federation federation, int year, String category, Input a, Input b,
                                   LocalDate today, List<String> notPublished) {
        return build(federation, year, category, Scope.SEASON, a, b, today, notPublished);
    }

    /**
     * El comparativo en un período: un torneo (solo sus partidos y sus enfrentamientos), los últimos 5 partidos de cada
     * equipo o el año. Solo va lo del período elegido.
     */
    public static Comparison build(Federation federation, int year, String category, Scope scope, Input a, Input b,
                                   LocalDate today, List<String> notPublished) {
        String period = period(scope, year);
        Side sa = side(a, category, year, scope, period, today);
        Side sb = side(b, category, year, scope, period, today);
        // Los enfrentamientos: los del torneo elegido; con los últimos 5 o el año, los del año.
        List<HeadToHead> h2h = headToHead(inTournament(a, scope), inTournament(b, scope));
        List<String> notes = new ArrayList<>();
        if (scope.tournament() != null) {
            List<Competition> ca = inTournament(a, scope).data().stream().map(CompetitionData::competition).toList();
            List<Competition> cb = inTournament(b, scope).data().stream().map(CompetitionData::competition).toList();
            if (ca.stream().noneMatch(c -> cb.stream().anyMatch(o -> o.id() == c.id()))) {
                notes.add(sa.name() + " juega " + names(ca) + " y " + sb.name() + " juega " + names(cb)
                        + ": las cifras del " + period + " no son del mismo torneo.");
            }
        }
        return new Comparison(federation == null ? null : federation.name(), year, category, period,
                scope == Scope.LAST_5, today, sa, sb, h2h, summary(sa, sb, h2h, scope, period), notes, notPublished);
    }

    /** "Apertura", "Últimos 5 partidos", "Año 2026". */
    static String period(Scope scope, int year) {
        if (scope.tournament() != null) {
            return scope.tournament().label();
        }
        return scope == Scope.LAST_5 ? "Últimos " + Comparison.LAST_MATCHES + " partidos" : "Año " + year;
    }

    private static String names(List<Competition> comps) {
        return comps.stream().map(Competition::name).collect(Collectors.joining(" y "));
    }

    /** Lo del equipo en el torneo del período (todo el año si el período no es un torneo). */
    private static Input inTournament(Input in, Scope scope) {
        if (scope.tournament() == null) {
            return in;
        }
        return new Input(in.club(), in.teamKey(), in.data().stream()
                .filter(cd -> cd.competition().tournament() == scope.tournament()).toList(), in.logo());
    }

    /** Lo del equipo en el período: el torneo, o el año con solo sus últimos 5 partidos jugados, o el año. */
    static Input scoped(Input in, Scope scope) {
        if (scope != Scope.LAST_5) {
            return inTournament(in, scope);
        }
        Map<Competition, TeamRef> teams = StatsEngine.teamsOf(in.data(), in.club(), in.teamKey());
        List<Match> played = new ArrayList<>();
        for (CompetitionData cd : in.data()) {
            TeamRef t = teams.get(cd.competition());
            if (t != null) {
                cd.matches().stream().filter(m -> m.played() && m.involves(t.teamId())).forEach(played::add);
            }
        }
        played.sort(StatsEngine.CHRONO);
        Set<Match> last = new HashSet<>(played.subList(Math.max(0, played.size() - Comparison.LAST_MATCHES),
                played.size()));
        List<CompetitionData> data = in.data().stream().map(cd -> new CompetitionData(cd.competition(), cd.standings(),
                cd.standingsDate(), cd.matches().stream().filter(last::contains).toList(), cd.players(), cd.sheets(),
                cd.goalkeepers(), cd.startingKeepers())).toList();
        return new Input(in.club(), in.teamKey(), data, in.logo());
    }

    private static Side side(Input in, String category, int year, Scope scope, String period, LocalDate today) {
        TeamReport season = report(in, category, "Temporada " + year, in.data(), today, 0);
        if (season == null) {
            throw new IllegalArgumentException(in.club().name() + " no juega en " + category + " en " + year + ".");
        }
        Input chosen = scoped(in, scope);
        TeamReport report = report(chosen, category, period, chosen.data(), today, 0);
        if (report == null) {
            throw new IllegalArgumentException(in.club().name() + " no juega el " + period + " en " + category + " en "
                    + year + ".");
        }
        // El plantel del año dice quién es jugador y quién del cuerpo técnico.
        List<Competition> all = in.data().stream().map(CompetitionData::competition).toList();
        List<CardLine> cards = cards(chosen, season.squad(), all);
        ReportData.ClubInfo club = new ReportData.ClubInfo(in.club().id(), in.club().code(), in.club().name(), in.logo());
        return new Side(club, in.teamKey(), season.name(), new View(report, cards));
    }

    private static TeamReport report(Input in, String category, String label, List<CompetitionData> data,
                                     LocalDate today, int lastMatches) {
        return StatsEngine.build(in.club(), category, label, data, today, lastMatches, in.teamKey()).teams().stream()
                .findFirst().orElse(null);
    }

    /**
     * Cada tarjeta del equipo en sus partidos con acta, en orden, con el marcador en ese momento. Es de un jugador si
     * figura en el plantel ({@code squad}: los de las planillas de sus partidos); si no, del cuerpo técnico.
     */
    static List<CardLine> cards(Input in, List<PlayerLine> squad, List<Competition> all) {
        Map<Competition, TeamRef> teams = StatsEngine.teamsOf(in.data(), in.club(), in.teamKey());
        Set<Integer> squadIds = squad.stream().map(PlayerLine::playerId).filter(id -> id > 0).collect(Collectors.toSet());
        Set<Set<String>> squadNames = squad.stream().map(p -> words(p.name())).collect(Collectors.toSet());
        List<CardLine> out = new ArrayList<>();
        for (CompetitionData cd : in.data()) {
            TeamRef t = teams.get(cd.competition());
            if (t == null) {
                continue;
            }
            String tournament = cd.competition().tournamentLabel(all);
            List<Match> played = cd.matches().stream().filter(m -> m.played() && m.involves(t.teamId()))
                    .sorted(StatsEngine.CHRONO).toList();
            for (Match m : played) {
                boolean home = m.homeTeamId() == t.teamId();
                String opponent = home ? m.awayName() : m.homeName();
                int own = 0;
                int opp = 0;
                for (MatchEvent e : chronological(cd.sheets().getOrDefault(m.idp(), List.of()))) {
                    boolean mine = e.teamId() == t.teamId();
                    if (e.type() == EventType.GOAL) {
                        if (mine) {
                            own++;
                        } else {
                            opp++;
                        }
                    } else if (mine && card(e.type()) != null) {
                        boolean player = (e.playerId() > 0 && squadIds.contains(e.playerId()))
                                || squadNames.contains(words(e.player()));
                        out.add(new CardLine(tournament, m.date(), opponent, e.player(), !player, card(e.type()),
                                e.period(), e.minute(), e.second(), own, opp));
                    }
                }
            }
        }
        out.sort(Comparator.comparing(CardLine::date, Comparator.nullsLast(Comparator.naturalOrder())));
        return out;
    }

    private static List<MatchEvent> chronological(List<MatchEvent> events) {
        return events.stream().sorted(Comparator.comparingInt(MatchEvent::period).thenComparingInt(MatchEvent::minute)
                .thenComparingInt(MatchEvent::second)).toList();
    }

    private static String card(EventType type) {
        return switch (type) {
            case YELLOW_CARD -> "Amarilla";
            case BLUE_CARD -> "Azul";
            case RED_CARD -> "Roja";
            default -> null;
        };
    }

    /** Las palabras del nombre, sin acentos ni mayúsculas: "PIRES MATEUS, Ana" = "Ana Pires Mateus". */
    static Set<String> words(String name) {
        if (name == null) {
            return Set.of();
        }
        String plain = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).replaceAll("[^a-zñ0-9 ]", " ");
        return new HashSet<>(Arrays.stream(plain.trim().split("\\s+")).filter(w -> !w.isEmpty()).toList());
    }

    /** Los partidos del año entre los dos equipos (jugados y por jugar), en orden. */
    static List<HeadToHead> headToHead(Input a, Input b) {
        Map<Competition, TeamRef> teamsA = StatsEngine.teamsOf(a.data(), a.club(), a.teamKey());
        Set<Integer> idsB = StatsEngine.teamsOf(b.data(), b.club(), b.teamKey()).values().stream()
                .map(TeamRef::teamId).collect(Collectors.toSet());
        List<Competition> all = a.data().stream().map(CompetitionData::competition).toList();
        List<Map.Entry<Match, HeadToHead>> out = new ArrayList<>();
        for (CompetitionData cd : a.data()) {
            TeamRef t = teamsA.get(cd.competition());
            if (t == null) {
                continue;
            }
            for (Match m : cd.matches()) {
                int other = m.homeTeamId() == t.teamId() ? m.awayTeamId() : m.awayTeamId() == t.teamId() ? m.homeTeamId() : -1;
                if (!idsB.contains(other)) {
                    continue;
                }
                List<Scorer> scorers = scorers(m, cd.sheets().getOrDefault(m.idp(), List.of()));
                out.add(Map.entry(m, new HeadToHead(cd.competition().tournamentLabel(all), m.round(), m.date(), m.time(),
                        m.venue(), m.homeName(), m.awayName(), m.homeTeamId() == t.teamId(), m.homeGoals(),
                        m.awayGoals(), scorers)));
            }
        }
        out.sort(Map.Entry.comparingByKey(StatsEngine.CHRONO));
        return out.stream().map(Map.Entry::getValue).toList();
    }

    private static List<Scorer> scorers(Match m, List<MatchEvent> events) {
        Map<String, int[]> goals = new LinkedHashMap<>();   // "L|nombre" -> goles
        for (MatchEvent e : chronological(events)) {
            if (e.type() == EventType.GOAL && e.player() != null && !e.player().isBlank()) {
                goals.computeIfAbsent((e.teamId() == m.homeTeamId() ? "L|" : "V|") + e.player(), k -> new int[1])[0]++;
            }
        }
        return goals.entrySet().stream()
                .map(e -> new Scorer(e.getKey().startsWith("L|"), e.getKey().substring(2), e.getValue()[0]))
                .sorted(Comparator.comparing((Scorer s) -> !s.home()).thenComparing(s -> -s.goals()))
                .toList();
    }

    /** El resumen del período: a quién le va mejor, la tabla, los enfrentamientos y el próximo partido entre los dos. */
    static List<String> summary(Side a, Side b, List<HeadToHead> h2h, Scope scope, String period) {
        List<String> out = new ArrayList<>();
        ReportData.TeamRecord ra = a.view().report().total();
        ReportData.TeamRecord rb = b.view().report().total();
        boolean last = scope == Scope.LAST_5;
        String where = last ? "en sus últimos " + Comparison.LAST_MATCHES + " partidos"
                : scope.tournament() != null ? "en el " + period : "en el año";
        String form = where + " " + a.name() + " " + record(ra) + " y " + b.name() + " " + record(rb) + ".";
        int byPoints = Integer.compare(ra.points(), rb.points());
        int cmp = byPoints != 0 ? byPoints : Integer.compare(ra.goalDiff(), rb.goalDiff());
        out.add(cmp == 0 ? (last ? "Llegan parejos: " : "Van parejos: ") + form
                : (cmp > 0 ? a.name() : b.name()) + (last ? " llega mejor: " : " viene mejor: ") + form);

        // La tabla, solo en un torneo (la del torneo elegido).
        if (scope.tournament() != null) {
            position(a).ifPresent(pa -> position(b).ifPresent(pb -> out.add("En el " + period + ", " + a.name()
                    + " está " + pa + " y " + b.name() + " " + pb + ".")));
        }

        String in = scope.tournament() != null ? "en el " + period : "en el año";
        List<HeadToHead> played = h2h.stream().filter(HeadToHead::played).toList();
        if (played.isEmpty()) {
            out.add("Todavía no se enfrentaron " + in + ".");
        } else {
            int winsA = 0;
            int winsB = 0;
            int goalsA = 0;
            int goalsB = 0;
            for (HeadToHead m : played) {
                int ga = m.goalsA();
                int gb = m.goalsB();
                goalsA += ga;
                goalsB += gb;
                winsA += ga > gb ? 1 : 0;
                winsB += gb > ga ? 1 : 0;
            }
            int draws = played.size() - winsA - winsB;
            out.add("Se enfrentaron " + times(played.size()) + " " + in + ": " + tally(a.name(), winsA, b.name(), winsB, draws)
                    + ", con " + goalsA + " goles de " + a.name() + " y " + goalsB + " de " + b.name() + ".");
        }
        h2h.stream().filter(m -> !m.played()).findFirst().ifPresent(m -> out.add("El próximo enfrentamiento es por la fecha "
                + m.round() + " del " + m.tournament()
                + (m.date() == null ? "" : ", el " + m.date().format(java.time.format.DateTimeFormatter.ofPattern("dd/MM")))
                + ", en cancha de " + m.home() + "."));
        return out;
    }

    private static java.util.Optional<String> position(Side s) {
        return s.view().report().standings().stream().findFirst()
                .map(st -> st.position() + "º de " + st.groupSize());
    }

    private static String record(ReportData.TeamRecord r) {
        return "ganó " + r.won() + ", empató " + r.drawn() + " y perdió " + r.lost() + " (" + r.goalsFor() + " goles a favor, "
                + r.goalsAgainst() + " en contra)";
    }

    private static String times(int n) {
        return n == 1 ? "una vez" : n + " veces";
    }

    /** "Argentinos ganó 2", "Comunicaciones ganó 1 y empataron 1". */
    private static String tally(String a, int winsA, String b, int winsB, int draws) {
        List<String> parts = new ArrayList<>();
        if (winsA > 0) {
            parts.add(a + " ganó " + winsA);
        }
        if (winsB > 0) {
            parts.add(b + " ganó " + winsB);
        }
        if (draws > 0) {
            parts.add("empataron " + draws);
        }
        return parts.size() == 1 ? parts.getFirst()
                : String.join(", ", parts.subList(0, parts.size() - 1)) + " y " + parts.getLast();
    }
}
