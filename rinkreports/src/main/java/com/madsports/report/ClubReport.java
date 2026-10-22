package com.madsports.report;

import com.madsports.analysis.AnalysisResult;
import com.madsports.sidgad.model.Federation;
import com.madsports.stats.ReportData;
import com.madsports.stats.ReportData.PlayerLine;
import com.madsports.stats.ReportData.TeamRecord;
import com.madsports.stats.ReportData.TeamReport;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

/**
 * Informe del club completo: primero cada rama (Masculino, Femenino) y, dentro de la rama, cada categoría del club
 * (cada equipo: "Primera", "Infantil B"), con sus partidos, goles, faltas y tarjetas, los top 3 de goleo, libres,
 * penales y disciplina, y las fortalezas y debilidades con el análisis escrito.
 *
 * @param period "Temporada 2026 · Apertura + Clausura"
 */
public record ClubReport(ReportData.ClubInfo club, String period, LocalDate generatedOn, Federation federation,
                         List<Branch> branches, List<String> notPublished, List<ReportData.Source> sources) {

    /** Una rama ("Masculino") con sus categorías, en el orden de la federación (de Infantil a Primera). */
    public record Branch(String name, List<CategoryBlock> categories) {
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
    public record CategoryBlock(String name, ReportData data, TeamReport team, AnalysisResult analysis) {

        public TeamRecord record() {
            return team.total();
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
