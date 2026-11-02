package com.madsports.compare;

import com.madsports.catalog.CatalogService.ClubView;
import com.madsports.compare.Comparison.CardLine;
import com.madsports.compare.Comparison.HeadToHead;
import com.madsports.compare.Comparison.Scorer;
import com.madsports.compare.Comparison.Side;
import com.madsports.compare.Comparison.View;
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

    public static Comparison build(Federation federation, int year, String category, Input a, Input b,
                                   LocalDate today, List<String> notPublished) {
        Side sa = side(a, category, year, today);
        Side sb = side(b, category, year, today);
        List<HeadToHead> h2h = headToHead(a, b);
        List<String> notes = new ArrayList<>();
        Competition ca = currentCompetition(a);
        Competition cb = currentCompetition(b);
        if (ca != null && cb != null && ca.id() != cb.id()) {
            notes.add(sa.name() + " juega " + ca.name() + " y " + sb.name() + " juega " + cb.name()
                    + ": las cifras del torneo actual no son del mismo torneo.");
        }
        return new Comparison(federation == null ? null : federation.name(), year, category, today, sa, sb, h2h,
                summary(sa, sb, h2h), notes, notPublished);
    }

    private static Side side(Input in, String category, int year, LocalDate today) {
        TeamReport season = report(in, category, "Temporada " + year, in.data(), today, 0);
        if (season == null) {
            throw new IllegalArgumentException(in.club().name() + " no juega en " + category + " en " + year + ".");
        }
        Competition current = currentCompetition(in);
        List<CompetitionData> currentData = current == null ? in.data()
                : in.data().stream().filter(cd -> cd.competition().id() == current.id()).toList();
        List<Competition> all = in.data().stream().map(CompetitionData::competition).toList();
        String currentLabel = current == null ? "Temporada " + year : current.tournamentLabel(all);
        TeamReport currentReport = report(in, category, currentLabel, currentData, today, 0);
        TeamReport last5 = report(in, category, "Últimos " + Comparison.LAST_MATCHES + " partidos", in.data(), today,
                Comparison.LAST_MATCHES);
        List<CardLine> cards = cards(in, season.squad(), all);
        List<CardLine> currentCards = cards(new Input(in.club(), in.teamKey(), currentData, in.logo()), season.squad(), all);
        ReportData.ClubInfo club = new ReportData.ClubInfo(in.club().id(), in.club().code(), in.club().name(), in.logo());
        return new Side(club, in.teamKey(), season.name(), currentLabel, new View(currentReport, currentCards),
                new View(season, cards), last5);
    }

    private static TeamReport report(Input in, String category, String label, List<CompetitionData> data,
                                     LocalDate today, int lastMatches) {
        return StatsEngine.build(in.club(), category, label, data, today, lastMatches, in.teamKey()).teams().stream()
                .findFirst().orElse(null);
    }

    /** La competición del último partido jugado del equipo; null si todavía no jugó. */
    static Competition currentCompetition(Input in) {
        Map<Competition, TeamRef> teams = StatsEngine.teamsOf(in.data(), in.club(), in.teamKey());
        Competition best = null;
        Match last = null;
        for (CompetitionData cd : in.data()) {
            TeamRef t = teams.get(cd.competition());
            if (t == null) {
                continue;
            }
            for (Match m : cd.matches()) {
                if (m.played() && m.involves(t.teamId()) && (last == null || StatsEngine.CHRONO.compare(m, last) > 0)) {
                    last = m;
                    best = cd.competition();
                }
            }
        }
        return best;
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

    /** El resumen: quién llega mejor, la tabla, los enfrentamientos y el próximo partido entre los dos. */
    static List<String> summary(Side a, Side b, List<HeadToHead> h2h) {
        List<String> out = new ArrayList<>();
        ReportData.TeamRecord ra = a.last5().total();
        ReportData.TeamRecord rb = b.last5().total();
        String form = "en sus últimos " + Comparison.LAST_MATCHES + " partidos " + a.name() + " " + record(ra)
                + " y " + b.name() + " " + record(rb) + ".";
        int byPoints = Integer.compare(ra.points(), rb.points());
        int cmp = byPoints != 0 ? byPoints : Integer.compare(ra.goalDiff(), rb.goalDiff());
        out.add(cmp == 0 ? "Llegan parejos: " + form
                : (cmp > 0 ? a.name() : b.name()) + " llega mejor: " + form);

        position(a).ifPresent(pa -> position(b).ifPresent(pb -> out.add(
                a.currentTournament().equals(b.currentTournament())
                        ? "En el " + a.currentTournament() + ", " + a.name() + " está " + pa + " y " + b.name() + " " + pb + "."
                        : a.name() + " está " + pa + " en el " + a.currentTournament() + " y " + b.name() + " " + pb
                        + " en el " + b.currentTournament() + ".")));

        List<HeadToHead> played = h2h.stream().filter(HeadToHead::played).toList();
        if (played.isEmpty()) {
            out.add("Todavía no se enfrentaron en el año.");
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
            out.add("Se enfrentaron " + times(played.size()) + " en el año: " + tally(a.name(), winsA, b.name(), winsB, draws)
                    + ", con " + goalsA + " goles de " + a.name() + " y " + goalsB + " de " + b.name() + ".");
        }
        h2h.stream().filter(m -> !m.played()).findFirst().ifPresent(m -> out.add("El próximo enfrentamiento es por la fecha "
                + m.round() + " del " + m.tournament()
                + (m.date() == null ? "" : ", el " + m.date().format(java.time.format.DateTimeFormatter.ofPattern("dd/MM")))
                + ", en cancha de " + m.home() + "."));
        return out;
    }

    private static java.util.Optional<String> position(Side s) {
        return s.current().report().standings().stream().findFirst()
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
