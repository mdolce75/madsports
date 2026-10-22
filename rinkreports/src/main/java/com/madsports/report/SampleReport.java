package com.madsports.report;

import com.madsports.analysis.AnalysisResult;
import com.madsports.analysis.RuleBasedAnalyst;
import com.madsports.player.PlayerReport;
import com.madsports.player.SamplePlayerData;
import com.madsports.sidgad.model.Competition.Category;
import com.madsports.sidgad.model.Federation;
import com.madsports.stats.ReportData;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/** Informe modelo con datos ficticios, para mostrar el formato sin consultar SIDGAD ni la API de Gemini. */
@Component
public class SampleReport {

    private final PdfRenderer renderer;
    private final ExcelExporter excel;
    private final RuleBasedAnalyst rules;
    private final Clock clock;
    private volatile Outputs club;
    private volatile Outputs player;

    /** El informe en sus tres formatos: en pantalla (HTML), PDF y Excel; armado el día {@code on}. */
    public record Outputs(LocalDate on, String html, byte[] pdf, byte[] xlsx) {
    }

    public SampleReport(PdfRenderer renderer, ExcelExporter excel, RuleBasedAnalyst rules, Clock clock) {
        this.renderer = renderer;
        this.excel = excel;
        this.rules = rules;
        this.clock = clock;
    }

    /**
     * Informe modelo del club completo: rama Masculino (Primera y Juvenil) y Femenino (Infantil). Se genera una vez por
     * día (las fechas del ejemplo son relativas a hoy).
     */
    public Outputs club() {
        LocalDate today = LocalDate.now(clock);
        Outputs o = club;
        if (o == null || !today.equals(o.on())) {
            synchronized (this) {
                o = club;
                if (o == null || !today.equals(o.on())) {
                    ClubReport r = clubReport(today);
                    o = new Outputs(today, renderer.clubHtml(r, true), renderer.clubPdf(r, true), excel.club(r));
                    club = o;
                }
            }
        }
        return o;
    }

    public byte[] pdf() {
        return club().pdf();
    }

    ClubReport clubReport(LocalDate today) {
        String logo = Crests.sample("CE");
        ClubReport.CategoryBlock primera = block("Primera", SampleData.build(today));
        ClubReport.CategoryBlock juvenil = block("Juvenil",
                SampleData.build(today, new Category("JUVENIL", "MASCULINO"), 2027, 9011));
        ClubReport.CategoryBlock infantil = block("Infantil",
                SampleData.build(today, new Category("INFANTIL", "FEMENINO"), 2028, 9021));
        ReportData.ClubInfo club = primera.data().club();
        return new ClubReport(new ReportData.ClubInfo(club.id(), club.code(), club.name(), logo),
                "Temporada " + today.getYear() + " · Apertura + Clausura", today, Federation.FPP,
                List.of(new ClubReport.Branch("Masculino", List.of(primera, juvenil)),
                        new ClubReport.Branch("Femenino", List.of(infantil))),
                List.of(), List.of());
    }

    private ClubReport.CategoryBlock block(String name, ReportData data) {
        return new ClubReport.CategoryBlock(name, data, data.teams().getFirst(), analysis(data));
    }

    /** Informe modelo de jugador ("Tomás Ejemplo", de Juniors, que también juega en Primera); uno por día. */
    public Outputs player() {
        LocalDate today = LocalDate.now(clock);
        Outputs o = player;
        if (o == null || !today.equals(o.on())) {
            synchronized (this) {
                o = player;
                if (o == null || !today.equals(o.on())) {
                    PlayerReport r = SamplePlayerData.build(today, Crests.sample("CE"));
                    o = new Outputs(today, renderer.playerHtml(r, true), renderer.playerPdf(r, true), excel.player(r));
                    player = o;
                }
            }
        }
        return o;
    }

    public byte[] playerPdf() {
        return player().pdf();
    }

    /** El texto de análisis sale del analista por reglas (no consume la API), con la fuente aclarada. */
    AnalysisResult analysis(ReportData data) {
        AnalysisResult a = rules.analyze(data);
        return new AnalysisResult("texto de ejemplo — en los informes reales lo redacta la IA (Gemini)",
                a.summary(), a.strengths(), a.weaknesses(), a.keyPlayers(), a.nextOpponentPreview(), a.trend(),
                a.foulsAnalysis());
    }
}
