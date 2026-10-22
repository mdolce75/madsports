package com.madsports.report;

import com.madsports.analysis.RuleBasedAnalyst;
import com.madsports.stats.ReportData;
import com.madsports.stats.ReportData.PlayerLine;
import com.madsports.stats.ReportData.TeamReport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import com.madsports.player.PlayerReport;
import com.madsports.player.SamplePlayerData;
import java.util.List;
import com.madsports.stats.StatsEngine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@SpringBootTest(classes = InformesTestConfig.class)
class SampleReportTest {

    static final LocalDate HOY = LocalDate.of(2026, 10, 13);

    @Autowired
    SampleReport sample;

    @Autowired
    PdfRenderer renderer;

    @Autowired
    RuleBasedAnalyst rules;

    @Test
    void losDatosFicticiosSonConsistentes() {
        ReportData d = SampleData.build(HOY);
        TeamReport t = d.teams().getFirst();

        assertThat(d.club().name()).isEqualTo("CLUB EJEMPLO");
        assertThat(d.teams()).hasSize(1);
        assertThat(t.total().played()).isEqualTo(11);
        assertThat(t.upcoming()).hasSize(3);
        assertThat(t.byTorneo()).hasSize(2);
        assertThat(t.standings()).hasSize(2).allSatisfy(s -> assertThat(s.outdated()).isFalse());
        // Cada jugador aparece una sola vez (Apertura + Clausura sumados) y sus goles suman los del equipo.
        assertThat(t.players()).extracting(PlayerLine::name).doesNotHaveDuplicates().hasSize(9);
        assertThat(t.players()).allSatisfy(p -> assertThat(p.games()).isGreaterThan(7));
        assertThat(t.players().stream().mapToInt(PlayerLine::goals).sum()).isEqualTo(t.total().goalsFor());
        // Una ficha no publicada, y la tabla por minuto igual cierra con el récord.
        assertThat(t.timeProfile().matchesWithSheet()).isEqualTo(10);
        assertThat(t.timeProfile().hasUnrecordedGoals()).isTrue();
        assertThat(t.timeProfile().totalGoalsFor()).isEqualTo(t.total().goalsFor());
        assertThat(t.timeProfile().totalGoalsAgainst()).isEqualTo(t.total().goalsAgainst());
        assertThat(t.timeProfile().goalMismatches()).isZero();
        assertThat(d.notPublished()).containsExactly("Actas de partido: 1 de 11");
        // Determinístico: mismo día, mismo informe.
        assertThat(SampleData.build(HOY)).isEqualTo(d);
    }

    @Autowired
    com.fasterxml.jackson.databind.ObjectMapper mapper;

    @Test
    void elJsonQueRecibeLaIaTraeLasCifrasDerivadas() throws Exception {
        ReportData d = SampleData.build(HOY);
        TeamReport t = d.teams().getFirst();
        com.fasterxml.jackson.databind.JsonNode json = mapper.readTree(mapper.writeValueAsString(d));
        com.fasterxml.jackson.databind.JsonNode team = json.at("/teams/0");

        assertThat(team.at("/total/points").asInt()).isEqualTo(t.total().points());
        assertThat(team.at("/total/effectiveness").asDouble()).isEqualTo(t.total().effectiveness());
        assertThat(team.at("/total/goalsForPerGame").asDouble()).isEqualTo(t.total().goalsForPerGame());
        assertThat(team.at("/results/0/score").asText()).isEqualTo(t.results().getFirst().score());
        assertThat(team.at("/setPieces/directFreePct").isNumber()).isTrue();
        assertThat(team.at("/timeProfile/shortHandedMinutesPerGame").isNumber()).isTrue();
        assertThat(team.at("/timeProfile/totalGoalsFor").asInt()).isEqualTo(t.total().goalsFor());
        // Se puede volver a leer (los derivados se ignoran al deserializar).
        assertThat(mapper.readValue(mapper.writeValueAsString(d), ReportData.class)).isEqualTo(d);
    }

    @Test
    void analisisEscritoDeLasFaltas() {
        ReportData d = StatsEngine.build(com.madsports.stats.StatsEngineTest.VELEZ, "Primera Masculino", "Clausura",
                List.of(com.madsports.stats.StatsEngineTest.data(779)), HOY);
        List<String> text = rules.foulsAnalysis(d);

        assertThat(text).hasSize(4);
        assertThat(text.get(0)).contains("8,00 faltas por partido (16 en 2 partidos con ficha)", "6,00 por partido",
                "comete más faltas que sus rivales");
        assertThat(text.get(1)).contains("1er tiempo", "2do");
        assertThat(text.get(2)).contains("perdiendo", "Comete faltas más seguido cuando va perdiendo");
        assertThat(text.get(3)).isEqualTo("No recibió tarjetas azules en esos partidos.");
        assertThat(rules.analyze(d).foulsAnalysis()).isEqualTo(text);
    }

    @Test
    void informeDeEjemploDeJugador() throws Exception {
        PlayerReport r = SamplePlayerData.build(HOY, Crests.sample("CE"));

        // Juniors que también juega en Primera: las dos categorías, en orden de nivel, y el total coherente.
        assertThat(r.categories()).extracting(PlayerReport.Line::category)
                .containsExactly("Juniors Masculino", "Primera Masculino");
        PlayerReport.Line t = r.total();
        assertThat(t.played()).isEqualTo(r.matches().size()).isEqualTo(t.won() + t.drawn() + t.lost());
        assertThat(t.goals()).isEqualTo(r.matches().stream().mapToInt(PlayerReport.MatchRow::goals).sum());
        assertThat(t.yellowCards() + t.blueCards() + t.redCards()).isEqualTo(r.matches().stream()
                .mapToInt(m -> m.yellowCards() + m.blueCards() + m.redCards()).sum());
        // Cada gol y tarjeta está en un tramo o en "sin minuto registrado".
        PlayerReport.Minutes mn = r.minutes();
        assertThat(mn.buckets().stream().mapToInt(PlayerReport.Bucket::goals).sum() + mn.unrecorded().goals())
                .isEqualTo(t.goals());
        assertThat(mn.buckets().stream().mapToInt(PlayerReport.Bucket::cards).sum() + mn.unrecorded().cards())
                .isEqualTo(t.cards());
        assertThat(t.hasSetPieces()).isTrue();
        assertThat(r.notes()).anyMatch(n -> n.startsWith("Categorías revisadas: Juniors, Reserva y Primera Masculino"))
                .anyMatch(n -> n.contains("no tiene acta publicada"));

        // Un bloque por categoría con sus propias estadísticas, que suman el total.
        assertThat(r.blocks()).extracting(b -> b.line().category()).containsExactly("Juniors Masculino", "Primera Masculino");
        assertThat(r.blocks().stream().mapToInt(b -> b.matches().size()).sum()).isEqualTo(r.matches().size());
        assertThat(r.blocks().stream().mapToInt(b -> b.minutes().buckets().stream()
                .mapToInt(PlayerReport.Bucket::goals).sum() + b.minutes().unrecorded().goals()).sum()).isEqualTo(t.goals());
        assertThat(r.blocks().stream().mapToInt(b -> b.shortHanded().matches().size()).sum())
                .isEqualTo(r.shortHanded().matches().size());

        // Fortalezas y debilidades contra sus compañeros, con suficientes partidos para calificar.
        assertThat(r.indicators()).extracting(com.madsports.stats.ReportData.Indicator::area)
                .contains("Goleo", "Titularidad", "Disciplina", "Pelota parada", "Resultados");
        assertThat(r.indicators()).anyMatch(i -> i.verdict() != com.madsports.stats.ReportData.Verdict.MUESTRA_CHICA);
        assertThat(r.strengths().size() + r.weaknesses().size()).isPositive();

        // Por categoría: cada bloque compara contra los compañeros de esa categoría (sus goles por partido son los
        // de la categoría, no los generales).
        for (PlayerReport.CategoryBlock b : r.blocks()) {
            assertThat(b.indicators()).isNotEmpty();
            assertThat(b.indicators()).filteredOn(i -> i.metric().equals("Goles por partido")).singleElement()
                    .satisfies(i -> assertThat(i.value()).isCloseTo(b.line().goalsPerGame(), within(1e-9)));
        }

        String html = renderer.playerHtml(r, true);
        assertThat(html).contains("Aspectos a mejorar", "Compañeros");
        // Primero las de cada categoría y al final las generales.
        int juniors = html.indexOf("Fortalezas y debilidades en Juniors Masculino");
        int primera = html.indexOf("Fortalezas y debilidades en Primera Masculino");
        int general = html.indexOf("Fortalezas y debilidades generales");
        assertThat(juniors).isPositive().isLessThan(primera);
        assertThat(primera).isLessThan(general);
        assertThat(html).contains("INFORME DE EJEMPLO — DATOS FICTICIOS", "Informe de ejemplo.", "Tomás Ejemplo",
                "CLUB EJEMPLO", "Penales y libres directos", "Goles por tramos de 5 minutos",
                "Tarjetas por tramos de 5 minutos");
        assertThat(renderer.playerHtml(r, false)).doesNotContain("DATOS FICTICIOS");

        byte[] pdf = sample.playerPdf();
        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
        Files.write(Path.of("target", "informe-jugador-modelo.pdf"), pdf);
    }

    @Test
    void elPdfLlevaLaMarcaDeDatosFicticios() throws Exception {
        ReportData d = SampleData.build(HOY);
        String html = renderer.html(d, sample.analysis(d), true);

        assertThat(html).contains("Análisis de faltas", "Según el marcador");
        // Gráficos: resultados, partido a partido, reparto del gol, goles por tramo, faltas por tramo y tiempo según el marcador.
        assertThat(html.split("src=\"data:image/png;base64,", -1)).hasSize(6 + 1);
        assertThat(html).contains("INFORME DE EJEMPLO — DATOS FICTICIOS", "Informe de ejemplo.",
                "CLUB EJEMPLO (CEJ)", "texto de ejemplo", "Sin minuto registrado");
        assertThat(renderer.html(d, rules.analyze(d), false)).doesNotContain("DATOS FICTICIOS");
        // Con escudo: uno en la cabecera y uno en el título de cada equipo.
        String withCrest = renderer.html(d.withLogo(Crests.sample("CE")), sample.analysis(d), true);
        assertThat(withCrest).contains("Escudo de CLUB EJEMPLO");
        assertThat(withCrest.split("src=\"data:image/png;base64,", -1)).hasSize(6 * d.teams().size() + 1 + d.teams().size() + 1);

        byte[] pdf = sample.pdf();
        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
        Files.write(Path.of("target", "informe-modelo.pdf"), pdf);
    }
}
