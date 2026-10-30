package com.madsports.report;

import com.madsports.analysis.AnalysisResult;
import com.madsports.sidgad.model.Competition;
import com.madsports.sidgad.model.Federation;
import com.madsports.stats.ReportData;
import com.madsports.stats.ReportData.PlayerLine;
import com.madsports.stats.ReportData.TeamRecord;
import com.madsports.stats.ReportData.TeamReport;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Informe del club completo: primero cada rama (Masculino, Femenino) y, dentro de la rama, cada categoría del club
 * (cada equipo: "Primera", "Infantil B"), con sus partidos, goles, faltas y tarjetas, los top 3 de goleo, libres,
 * penales y disciplina, y las fortalezas y debilidades con el análisis escrito.
 *
 * @param period "Temporada 2026 · Apertura + Clausura"
 */
public record ClubReport(ReportData.ClubInfo club, String period, LocalDate generatedOn, Federation federation,
                         List<Branch> branches, List<String> notPublished, List<ReportData.Source> sources) {

    /**
     * Semillero: los jugadores que, además de su categoría (la más baja en que jugó), ya juegan en categorías de arriba
     * de su rama, según los planteles de cada categoría. Ordenados por rama y por su categoría.
     */
    public List<PromotedPlayer> promoted() {
        List<PromotedPlayer> out = new ArrayList<>();
        for (Branch b : branches) {
            Map<Integer, List<Integer>> where = new LinkedHashMap<>();
            Map<Integer, String> names = new HashMap<>();
            Map<String, Integer> games = new HashMap<>();
            for (int i = 0; i < b.categories().size(); i++) {
                for (PlayerLine p : b.categories().get(i).team().squad()) {
                    if (p.games() <= 0) {
                        continue;
                    }
                    where.computeIfAbsent(p.playerId(), k -> new ArrayList<>()).add(i);
                    names.putIfAbsent(p.playerId(), p.name());
                    games.merge(p.playerId() + "|" + i, p.games(), Integer::sum);
                }
            }
            where.forEach((id, idx) -> {
                if (idx.size() < 2) {
                    return;
                }
                // Su categoría es la más baja según el orden de la federación (de Infantil a Primera).
                int base = idx.stream().min(Comparator.comparingInt(i -> level(b.categories().get(i).name())))
                        .orElseThrow();
                List<Appearance> upper = idx.stream().filter(i -> i != base).distinct()
                        .sorted(Comparator.comparingInt(i -> level(b.categories().get(i).name())))
                        .map(i -> new Appearance(b.categories().get(i).name(), games.getOrDefault(id + "|" + i, 0)))
                        .toList();
                out.add(new PromotedPlayer(names.get(id), b.name(), b.categories().get(base).name(),
                        games.getOrDefault(id + "|" + base, 0), upper, level(b.categories().get(base).name())));
            });
        }
        out.sort(Comparator.comparing(PromotedPlayer::branch).thenComparingInt(PromotedPlayer::order)
                .thenComparing(PromotedPlayer::name));
        return out;
    }

    /**
     * Lugar de una categoría ("Juvenil", "Infantil B") en el orden de la federación, de Infantil a la mayor; las que no
     * figuran van al final.
     */
    private int level(String category) {
        List<String> levels = federation.levels();
        for (int i = 0; i < levels.size(); i++) {
            String label = new Competition.Category(levels.get(i), "").levelLabel();
            if (category.equalsIgnoreCase(label) || category.toLowerCase().startsWith(label.toLowerCase() + " ")) {
                return i;
            }
        }
        return levels.size();
    }

    /** Un jugador del semillero: su categoría y las de arriba en que también juega (con sus partidos en cada una). */
    public record PromotedPlayer(String name, String branch, String category, int games, List<Appearance> upper,
                                 int order) {

        /** "Primera (5 PJ), Reserva (2 PJ)" */
        public String upperText() {
            return upper.stream().map(a -> a.category() + " (" + a.games() + " PJ)")
                    .collect(Collectors.joining(", "));
        }
    }

    public record Appearance(String category, int games) {
    }

    /** Una rama ("Masculino") con sus categorías, en el orden de la federación (de Infantil a Primera). */
    public record Branch(String name, List<CategoryBlock> categories) {
    }

    /** ¿Alguna categoría muestra la pelota parada? (la fuente distingue libres y penales) */
    public boolean hasSetPieces() {
        return branches.stream().flatMap(b -> b.categories().stream()).anyMatch(k -> k.data().hasSetPieces());
    }

    /** ¿Hay arqueros marcados en las planillas? (gestiondepsj no los marca) */
    public boolean hasKeepers() {
        return branches.stream().flatMap(b -> b.categories().stream())
                .anyMatch(k -> k.team().keepers() != null && !k.team().keepers().isEmpty());
    }

    /** Un jugador en un top 3: el valor por el que se ordena y las cantidades que lo explican. */
    public record Ranked(PlayerLine player, double value, int count, int of) {
    }

    /**
     * Una categoría del club (un equipo).
     *
     * @param name     "Primera", "Infantil B"
     * @param data     las cifras del informe de la categoría (las mismas que el informe de una categoría)
     * @param team     su equipo
     * @param analysis el análisis escrito (Gemini o, si no responde, el de reglas)
     */
    /**
     * Rachas de un equipo: las más largas del período y cómo viene.
     *
     * @param unbeaten    partidos seguidos sin perder (la más larga)
     * @param wins        partidos seguidos ganados
     * @param scoring     partidos seguidos convirtiendo
     * @param cleanSheets partidos sin goles en contra (en total)
     * @param current     cómo viene: "3 ganados seguidos", "2 empatados seguidos", "1 perdido" (vacío sin partidos)
     */
    public record Streaks(int unbeaten, int wins, int scoring, int cleanSheets, String current) {

        static Streaks of(List<ReportData.MatchLine> results) {
            List<ReportData.MatchLine> played = results.stream().filter(r -> r.goalsFor() != null).toList();
            int unbeaten = longest(played, r -> !"P".equals(r.outcome()));
            int wins = longest(played, r -> "G".equals(r.outcome()));
            int scoring = longest(played, r -> r.goalsFor() > 0);
            int clean = (int) played.stream().filter(r -> r.goalsAgainst() == 0).count();
            String current = "";
            if (!played.isEmpty()) {
                String last = played.getLast().outcome();
                int run = 0;
                for (int i = played.size() - 1; i >= 0 && played.get(i).outcome().equals(last); i--) {
                    run++;
                }
                String word = switch (last) {
                    case "G" -> run == 1 ? "ganado" : "ganados";
                    case "E" -> run == 1 ? "empatado" : "empatados";
                    default -> run == 1 ? "perdido" : "perdidos";
                };
                current = run + " " + word + (run == 1 ? "" : " seguidos");
            }
            return new Streaks(unbeaten, wins, scoring, clean, current);
        }

        private static int longest(List<ReportData.MatchLine> played, Predicate<ReportData.MatchLine> ok) {
            int best = 0;
            int run = 0;
            for (ReportData.MatchLine r : played) {
                run = ok.test(r) ? run + 1 : 0;
                best = Math.max(best, run);
            }
            return best;
        }
    }

    public record CategoryBlock(String name, ReportData data, TeamReport team, AnalysisResult analysis) {

        public TeamRecord record() {
            return team.total();
        }

        /** Rachas de la categoría, con los resultados en orden de fecha. */
        public Streaks streaks() {
            return Streaks.of(team.results());
        }

        private double perGame(int value, int matches) {
            return matches == 0 ? 0 : (double) value / matches;
        }

        // ---------- Faltas: de las actas ----------

        public int foulsOwn() {
            return team.fouls().own();
        }

        public int foulsRival() {
            return team.fouls().opponent();
        }

        /** Partidos con acta, sobre los que se cuentan las faltas y las amarillas. */
        public int sheetMatches() {
            return team.fouls().matches();
        }

        public double foulsOwnPerGame() {
            return perGame(foulsOwn(), sheetMatches());
        }

        public double foulsRivalPerGame() {
            return perGame(foulsRival(), sheetMatches());
        }

        // ---------- Tarjetas: amarillas de las actas; azules y rojas, de las estadísticas del torneo ----------

        public int yellowCards() {
            return team.squad().stream().mapToInt(PlayerLine::yellowCards).sum();
        }

        public int blueCards() {
            return team.discipline().blueCards();
        }

        public int redCards() {
            return team.discipline().redCards();
        }

        public double yellowPerGame() {
            return perGame(yellowCards(), sheetMatches());
        }

        public double bluePerGame() {
            return perGame(blueCards(), record().played());
        }

        public double redPerGame() {
            return perGame(redCards(), record().played());
        }

        // ---------- Top 3 ----------

        /** Mejor promedio de gol (goles por partido); a igual promedio, más goles. Sin arqueros. */
        public List<Ranked> topScorers() {
            return team.players().stream().filter(p -> p.goals() > 0 && p.games() > 0)
                    .sorted(Comparator.comparingDouble(PlayerLine::goalsPerGame).reversed()
                            .thenComparing(Comparator.comparingInt(PlayerLine::goals).reversed())
                            .thenComparing(PlayerLine::name))
                    .limit(3).map(p -> new Ranked(p, p.goalsPerGame(), p.goals(), p.games())).toList();
        }

        /** Mejor efectividad en libres directos (convertidos sobre tirados); a igual efectividad, más tirados. */
        public List<Ranked> topDirectFree() {
            return effectivenessPct(team.squad().stream().filter(p -> p.directFreeTaken() > 0)
                    .map(p -> new Ranked(p, 100.0 * p.directFreeScored() / p.directFreeTaken(), p.directFreeScored(),
                            p.directFreeTaken())).toList());
        }

        /** Mejor efectividad en penales; a igual efectividad, más tirados. */
        public List<Ranked> topPenalties() {
            return effectivenessPct(team.squad().stream().filter(p -> p.penaltiesTaken() > 0)
                    .map(p -> new Ranked(p, 100.0 * p.penaltiesScored() / p.penaltiesTaken(), p.penaltiesScored(),
                            p.penaltiesTaken())).toList());
        }

        private static List<Ranked> effectivenessPct(List<Ranked> ranked) {
            return ranked.stream().sorted(Comparator.comparingDouble(Ranked::value).reversed()
                            .thenComparing(Comparator.comparingInt(Ranked::of).reversed())
                            .thenComparing(r -> r.player().name()))
                    .limit(3).toList();
        }

        /** Disciplina: los 3 con más tarjetas (amarillas, azules y rojas sumadas); a igual cantidad, más rojas y azules. */
        public List<PlayerLine> topDiscipline() {
            return team.squad().stream().filter(p -> p.cards() > 0)
                    .sorted(Comparator.comparingInt(PlayerLine::cards).reversed()
                            .thenComparing(Comparator.comparingInt(PlayerLine::redCards).reversed())
                            .thenComparing(Comparator.comparingInt(PlayerLine::blueCards).reversed())
                            .thenComparing(PlayerLine::name))
                    .limit(3).toList();
        }
    }
}
