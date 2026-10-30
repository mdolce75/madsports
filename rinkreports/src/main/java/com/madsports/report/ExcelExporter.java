package com.madsports.report;

import com.madsports.analysis.AnalysisResult;
import com.madsports.player.PlayerReport;
import com.madsports.sidgad.model.Federation;
import com.madsports.source.DataSource;
import com.madsports.stats.ReportData;
import com.madsports.stats.ReportData.PlayerLine;
import com.madsports.stats.ReportData.TeamRecord;
import org.dhatim.fastexcel.BorderSide;
import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Los informes en Excel (.xlsx): las mismas cifras que el PDF, en tablas para filtrar y trabajar los números. El de club
 * lleva una hoja de resumen y una por categoría; el de jugador, el resumen por categoría, sus partidos, los tramos de
 * minutos y sus fortalezas.
 */
@Component
public class ExcelExporter {

    /** Verde contable de RinkReports. */
    private static final String LEDGER = "1F5F4F";
    private static final String LEDGER_SOFT = "E6F0EC";

    /** La fuente de los datos de la federación, como se nombra en el Excel (los tests prueban otra). */
    protected DataSource sourceOf(Federation f) {
        return f.source();
    }

    /** "VELEZ (CAVS)"; sin la sigla si es el mismo nombre (gestiondepsj no tiene siglas: "Aberastain"). */
    static String nameAndCode(String name, String code) {
        return code == null || code.isBlank() || code.equalsIgnoreCase(name) ? name : name + " (" + code + ")";
    }

    // ---------- Club ----------

    public byte[] club(ClubReport r) {
        return write(book -> {
            Sheet summaryOf = new Sheet(book, "Resumen");
            summaryOf.title("Informe de club · " + nameAndCode(r.club().name(), r.club().code()));
            summaryOf.meta("Período", r.period());
            summaryOf.meta("Federación", r.federation().name());
            summaryOf.meta("Generado", r.generatedOn());
            summaryOf.blank();
            summaryOf.header("Rama", "Categoría", "PJ", "G", "E", "P", "Efectividad", "GF", "GC", "GF por partido",
                    "GC por partido", "Faltas cometidas", "Faltas recibidas", "Amarillas", "Azules", "Rojas");
            for (ClubReport.Branch b : r.branches()) {
                for (ClubReport.CategoryBlock k : b.categories()) {
                    TeamRecord t = k.record();
                    summaryOf.row(b.name(), k.name(), t.played(), t.won(), t.drawn(), t.lost(), new Pct(t.effectiveness()),
                            t.goalsFor(), t.goalsAgainst(), t.goalsForPerGame(), t.goalsAgainstPerGame(), k.foulsOwn(),
                            k.foulsRival(), k.yellowCards(), k.blueCards(), k.redCards());
                }
            }
            summaryOf.widths(14, 16, 7, 7, 7, 7, 12, 7, 7, 14, 14, 16, 16, 11, 9, 9);
            List<ClubReport.PromotedPlayer> promoted = r.promoted();
            if (!promoted.isEmpty()) {
                summaryOf.blank();
                summaryOf.section("Semillero: juegan también en categorías de arriba");
                summaryOf.header("Jugador", "Rama", "Su categoría", "PJ", "También juega en");
                promoted.forEach(p -> summaryOf.row(p.name(), p.branch(), p.category(), p.games(), p.upperText()));
            }
            if (!r.notPublished().isEmpty()) {
                summaryOf.blank();
                summaryOf.section("Sin publicar en " + sourceOf(r.federation()).label());
                r.notPublished().forEach(n -> summaryOf.row(n));
            }
            for (ClubReport.Branch b : r.branches()) {
                for (ClubReport.CategoryBlock k : b.categories()) {
                    category(new Sheet(book, k.name() + " " + b.name()), r, b, k);
                }
            }
        });
    }

    private static void category(Sheet s, ClubReport r, ClubReport.Branch b, ClubReport.CategoryBlock k) {
        TeamRecord t = k.record();
        s.title(k.name() + " " + b.name() + " · " + r.club().name());
        s.meta("Período", r.period());
        s.blank();
        s.section("Partidos");
        s.header("Jugados", "Ganados", "Empatados", "Perdidos", "Efectividad");
        s.row(t.played(), t.won(), t.drawn(), t.lost(), new Pct(t.effectiveness()));
        s.blank();
        s.section("Goles");
        s.header("", "Total", "Por partido");
        s.row("A favor", t.goalsFor(), t.goalsForPerGame());
        s.row("En contra", t.goalsAgainst(), t.goalsAgainstPerGame());
        s.row("Diferencia", t.goalDiff(), t.played() == 0 ? 0.0 : (double) t.goalDiff() / t.played());
        s.blank();
        s.section("Faltas (partidos con acta: " + k.sheetMatches() + ")");
        s.header("", "Total", "Por partido");
        s.row("Cometidas", k.foulsOwn(), k.foulsOwnPerGame());
        s.row("Recibidas", k.foulsRival(), k.foulsRivalPerGame());
        s.blank();
        s.section("Tarjetas");
        s.header("", "Total", "Por partido");
        s.row("Amarillas", k.yellowCards(), k.yellowPerGame());
        s.row("Azules", k.blueCards(), k.bluePerGame());
        s.row("Rojas", k.redCards(), k.redPerGame());
        s.blank();
        ReportData.ScoreFlow sf = k.team().timeProfile().scoreFlow();
        if (sf != null && sf.hasData()) {
            s.section("Según el marcador (partidos con acta completa: " + sf.matches() + ")");
            s.header("", "Partidos", "Ganados", "Empatados", "Perdidos", "Puntos");
            s.row("Estuvo arriba", sf.led(), sf.ledWon(), sf.ledDrawn(), sf.ledLost(), -sf.pointsDropped());
            s.row("Estuvo abajo", sf.trailed(), sf.trailedWon(), sf.trailedDrawn(), sf.trailedLost(), sf.pointsRescued());
            s.blank();
            s.section("El primer gol");
            s.header("", "Partidos", "Ganados", "Empatados", "Perdidos", "Efectividad (%)");
            s.row("Convirtió primero", sf.scoredFirst(), sf.scoredFirstWon(), sf.scoredFirstDrawn(), sf.scoredFirstLost(),
                    sf.scoredFirstEffectiveness());
            s.row("Le convirtieron primero", sf.concededFirst(), sf.concededFirstWon(), sf.concededFirstDrawn(),
                    sf.concededFirstLost(), sf.concededFirstEffectiveness());
            s.blank();
            s.section("Sobre la hora (últimos 5 minutos)");
            s.header("", "Puntos");
            s.row("Ganados", sf.latePointsWon());
            s.row("Perdidos", -sf.latePointsLost());
            s.blank();
        }
        ClubReport.Streaks st = k.streaks();
        s.section("Rachas");
        s.header("", "Partidos");
        s.row("Invicto (seguidos)", st.unbeaten());
        s.row("Victorias seguidas", st.wins());
        s.row("Seguidos convirtiendo", st.scoring());
        s.row("Vallas invictas", st.cleanSheets());
        if (!st.current().isEmpty()) {
            s.text("Cómo viene: " + st.current() + ".");
        }
        s.blank();
        if (!k.team().rivals().isEmpty()) {
            s.section("Cara a cara");
            s.header("Rival", "PJ", "G", "E", "P", "GF", "GC");
            k.team().rivals().forEach(x -> s.row(x.opponent(), x.played(), x.won(), x.drawn(), x.lost(), x.goalsFor(),
                    x.goalsAgainst()));
            s.blank();
        }
        if (k.team().keepers() != null && !k.team().keepers().isEmpty()) {
            s.section("Arqueros (partidos de titular, según las planillas)");
            s.header("Arquero", "PJ de titular", "Goles recibidos", "Por partido", "Vallas invictas");
            k.team().keepers().forEach(x -> s.row(x.name(), x.matches(), x.goalsAgainst(), x.goalsAgainstPerGame(),
                    x.cleanSheets()));
            s.blank();
        }
        s.section("Top 3 · Promedio de gol");
        s.header("Jugador", "Goles", "Partidos", "Promedio");
        k.topScorers().forEach(x -> s.row(x.player().name(), x.count(), x.of(), x.value()));
        s.blank();
        s.section("Top 3 · Libres directos");
        s.header("Jugador", "Convertidos", "Tirados", "Efectividad");
        k.topDirectFree().forEach(x -> s.row(x.player().name(), x.count(), x.of(), new Pct(x.value())));
        s.blank();
        s.section("Top 3 · Penales");
        s.header("Jugador", "Convertidos", "Tirados", "Efectividad");
        k.topPenalties().forEach(x -> s.row(x.player().name(), x.count(), x.of(), new Pct(x.value())));
        s.blank();
        s.section("Top 3 · Disciplina");
        s.header("Jugador", "Amarillas", "Azules", "Rojas", "Total");
        k.topDiscipline().forEach(p -> s.row(p.name(), p.yellowCards(), p.blueCards(), p.redCards(), p.cards()));
        s.blank();
        s.section("Plantel");
        s.header("Jugador", "PJ", "Goles", "Promedio de gol", "Penales conv.", "Penales tir.", "Libres conv.",
                "Libres tir.", "Amarillas", "Azules", "Rojas");
        for (PlayerLine p : k.team().squad()) {
            s.row(p.name(), p.games(), p.goals(), p.goalsPerGame(), p.penaltiesScored(), p.penaltiesTaken(),
                    p.directFreeScored(), p.directFreeTaken(), p.yellowCards(), p.blueCards(), p.redCards());
        }
        s.blank();
        indicators(s, k.team().indicators());
        analysis(s, k.analysis());
        s.widths(30, 12, 12, 14, 13, 12, 12, 11, 11, 9, 9);
    }

    private static void analysis(Sheet s, AnalysisResult a) {
        if (a == null) {
            return;
        }
        s.blank();
        s.section("Análisis");
        s.text(a.summary());
        if (!a.strengths().isEmpty()) {
            s.label("Fortalezas");
            a.strengths().forEach(s::text);
        }
        if (!a.weaknesses().isEmpty()) {
            s.label("Aspectos a mejorar");
            a.weaknesses().forEach(s::text);
        }
    }

    // ---------- Jugador ----------

    public byte[] player(PlayerReport r) {
        return write(book -> {
            Sheet summaryOf = new Sheet(book, "Resumen");
            summaryOf.title("Informe de jugador · " + r.fullName());
            summaryOf.meta("Jugador en " + sourceOf(r.federation()).label(), r.playerName());
            summaryOf.meta("Club", r.club().name());
            summaryOf.meta("Período", r.period());
            summaryOf.meta("Generado", r.generatedOn());
            summaryOf.blank();
            summaryOf.header("Categoría", "PJ", "G", "E", "P", "Goles", "Goles por partido", "Incidencia de goles",
                    "Amarillas", "Azules", "Rojas", "Tarjetas por partido", "Incidencia de tarjetas", "Penales conv.",
                    "Penales tir.", "Libres conv.", "Libres tir.", "Sin acta");
            for (PlayerReport.Line l : r.categories()) {
                line(summaryOf, l);
            }
            line(summaryOf, r.total());
            if (!r.tournaments().isEmpty()) {
                summaryOf.blank();
                summaryOf.section("Por torneo");
                summaryOf.header("Torneo", "PJ", "G", "E", "P", "Goles", "Goles por partido", "Incidencia de goles",
                        "Amarillas", "Azules", "Rojas", "Tarjetas por partido", "Incidencia de tarjetas", "Penales conv.",
                        "Penales tir.", "Libres conv.", "Libres tir.", "Sin acta");
                for (PlayerReport.TournamentLine t : r.tournaments()) {
                    line(summaryOf, t.labeled());
                }
                line(summaryOf, r.total());
            }
            summaryOf.widths(26, 6, 6, 6, 6, 8, 16, 18, 10, 9, 8, 18, 20, 13, 12, 12, 11, 9);
            if (!r.notes().isEmpty()) {
                summaryOf.blank();
                summaryOf.section("Notas");
                r.notes().forEach(summaryOf::text);
            }

            Sheet matches = new Sheet(book, "Partidos");
            matches.header("Fecha", "Categoría", "Torneo", "Rival", "Condición", "GF", "GC", "Resultado", "Titular",
                    "Goles", "Amarillas", "Azules", "Rojas", "Sin acta");
            for (PlayerReport.MatchRow m : r.matches()) {
                matches.row(m.date(), m.category(), m.tournament(), m.opponent(), m.home() ? "Local" : "Visitante",
                        m.goalsFor(), m.goalsAgainst(), m.result(), m.fromSummary() ? "" : m.starter() ? "Sí" : "No",
                        m.goals(), m.yellowCards(), m.blueCards(), m.redCards(), m.fromSummary() ? "Sí" : "");
            }
            matches.widths(12, 18, 14, 30, 11, 6, 6, 10, 8, 7, 10, 8, 7, 9);
            matches.freezeHeader();

            Sheet minutes = new Sheet(book, "Minutos");
            minutes.title("Goles y tarjetas por tramo de 5 minutos");
            minutes.header("Tramo", "Goles", "Amarillas", "Azules", "Rojas", "Tarjetas");
            for (PlayerReport.Bucket b : r.minutes().buckets()) {
                bucket(minutes, b);
            }
            bucket(minutes, r.minutes().unrecorded());
            bucket(minutes, r.minutes().total());
            minutes.widths(22, 8, 10, 8, 8, 9);

            Sheet strengths = new Sheet(book, "Fortalezas");
            strengths.title("Fortalezas y debilidades");
            indicators(strengths, r.indicators());
            for (PlayerReport.CategoryBlock b : r.blocks()) {
                if (!b.indicators().isEmpty()) {
                    strengths.blank();
                    strengths.label(b.line().category());
                    indicators(strengths, b.indicators());
                }
            }
            strengths.widths(18, 30, 10, 14, 10, 14, 60);
        });
    }

    private static void line(Sheet s, PlayerReport.Line l) {
        s.row(l.category(), l.played(), l.won(), l.drawn(), l.lost(), l.goals(), l.goalsPerGame(), new Pct(l.goalShare()),
                l.yellowCards(), l.blueCards(), l.redCards(), l.cardsPerGame(), new Pct(l.cardShare()),
                l.penaltiesScored(), l.penaltiesTaken(), l.directFreeScored(), l.directFreeTaken(), l.withoutSheet());
    }

    private static void bucket(Sheet s, PlayerReport.Bucket b) {
        s.row(b.label(), b.goals(), b.yellowCards(), b.blueCards(), b.redCards(), b.cards());
    }

    private static void indicators(Sheet s, List<ReportData.Indicator> list) {
        if (list.isEmpty()) {
            return;
        }
        s.section("Fortalezas y debilidades");
        s.header("Área", "Indicador", "Valor", "Referencia", "Valor ref.", "Evaluación", "Por qué");
        list.forEach(i -> s.row(i.area(), i.metric(), i.value(), i.referenceLabel(), i.reference(),
                i.verdict().label(), i.explanation()));
    }

    // ---------- Escritura ----------

    /** Un porcentaje de 0 a 100: se guarda como número con formato de porcentaje. */
    record Pct(double percent) {
    }

    /** El libro y los nombres de hoja ya usados. */
    private record Book(Workbook wb, Set<String> names) {
    }

    private interface Content {
        void fill(Book book);
    }

    private static byte[] write(Content content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            Workbook wb = new Workbook(out, "RinkReports", "1.0");
            content.fill(new Book(wb, new HashSet<>()));
            wb.finish();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    /** Una hoja que se escribe de arriba hacia abajo. */
    private static final class Sheet {
        private final Worksheet ws;
        private int row;
        private int headerRow = -1;

        Sheet(Book book, String name) {
            ws = book.wb().newWorksheet(uniqueName(book.names(), name));
        }

        /** Nombre de hoja válido en Excel (hasta 31 caracteres, sin : \ / ? * [ ]) y sin repetir en el libro. */
        private static String uniqueName(Set<String> used, String name) {
            String base = name.replaceAll("[:\\\\/?*\\[\\]]", " ").trim();
            base = base.length() > 31 ? base.substring(0, 31).trim() : base;
            String candidate = base;
            for (int i = 2; !used.add(candidate.toLowerCase()); i++) {
                String suffix = " " + i;
                candidate = (base.length() + suffix.length() > 31 ? base.substring(0, 31 - suffix.length()) : base) + suffix;
            }
            return candidate;
        }

        void title(String text) {
            ws.value(row, 0, text);
            ws.style(row, 0).bold().fontSize(14).set();
            row++;
        }

        void meta(String label, Object value) {
            ws.value(row, 0, label);
            ws.style(row, 0).bold().set();
            cell(1, value);
            row++;
        }

        void section(String text) {
            ws.value(row, 0, text);
            ws.style(row, 0).bold().fontColor(LEDGER).fontSize(12).set();
            row++;
        }

        void label(String text) {
            ws.value(row, 0, text);
            ws.style(row, 0).bold().set();
            row++;
        }

        void text(String text) {
            ws.value(row, 0, text);
            row++;
        }

        void header(String... cols) {
            for (int c = 0; c < cols.length; c++) {
                ws.value(row, c, cols[c]);
                ws.style(row, c).bold().fontColor("FFFFFF").fillColor(LEDGER).set();
            }
            headerRow = row;
            row++;
        }

        void row(Object... cells) {
            for (int c = 0; c < cells.length; c++) {
                cell(c, cells[c]);
                ws.style(row, c).borderStyle(BorderSide.BOTTOM, "thin").borderColor(BorderSide.BOTTOM, "D3D9E2")
                        .format(format(cells[c])).set();
            }
            row++;
        }

        void blank() {
            row++;
        }

        void freezeHeader() {
            if (headerRow >= 0) {
                ws.freezePane(0, headerRow + 1);
            }
        }

        void widths(double... w) {
            for (int c = 0; c < w.length; c++) {
                ws.width(c, w[c]);
            }
        }

        private void cell(int c, Object v) {
            switch (v) {
                case null -> { }
                case Pct p -> ws.value(row, c, p.percent() / 100);
                case Number n -> ws.value(row, c, n);
                case LocalDate d -> ws.value(row, c, d);
                default -> ws.value(row, c, v.toString());
            }
        }

        private static String format(Object v) {
            return switch (v) {
                case Pct p -> "0%";
                case Double d -> "0.00";
                case Integer i -> "0";
                case LocalDate d -> "dd/mm/yyyy";
                case null, default -> "General";
            };
        }
    }
}
