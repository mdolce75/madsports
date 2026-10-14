package com.rinkmetrics.stats;

import com.rinkmetrics.stats.ReportData.Indicator;
import com.rinkmetrics.stats.ReportData.PlayerLine;
import com.rinkmetrics.stats.ReportData.TeamReport;
import com.rinkmetrics.stats.ReportData.Unit;
import com.rinkmetrics.stats.ReportData.Verdict;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StrengthsAnalysisTest {

    static Verdict rel(double value, double ref, boolean higherIsBetter, int sample) {
        return StrengthsAnalysis.relative("A", "M", value, "Liga", ref, Unit.PER_GAME, higherIsBetter, sample, "partidos")
                .verdict();
    }

    @Test
    void comparacionConLaReferencia() {
        // Más alto es mejor (goles a favor): +10% o más es fortaleza, -10% o menos debilidad.
        assertThat(rel(3.3, 3.0, true, 10)).isEqualTo(Verdict.FORTALEZA);
        assertThat(rel(3.2, 3.0, true, 10)).isEqualTo(Verdict.EN_LINEA);
        assertThat(rel(2.7, 3.0, true, 10)).isEqualTo(Verdict.DEBILIDAD);
        // Más bajo es mejor (goles en contra, tarjetas).
        assertThat(rel(2.0, 3.0, false, 10)).isEqualTo(Verdict.FORTALEZA);
        assertThat(rel(3.5, 3.0, false, 10)).isEqualTo(Verdict.DEBILIDAD);
        // Pocos casos.
        assertThat(rel(9.0, 3.0, true, 2)).isEqualTo(Verdict.MUESTRA_CHICA);
        // Referencia 0 (p. ej. sin minutos con uno más).
        assertThat(rel(0, 0, false, 5)).isEqualTo(Verdict.EN_LINEA);
        assertThat(rel(1.5, 0, false, 5)).isEqualTo(Verdict.DEBILIDAD);
    }

    @Test
    void explicacionConLasCifras() {
        Indicator i = StrengthsAnalysis.relative("Ataque", "Goles a favor por partido", 6.0, "Liga", 3.29,
                Unit.PER_GAME, true, 21, "partidos");
        assertThat(i.explanation()).isEqualTo("6,00 contra 3,29 (liga): 82% más.");
    }

    @Test
    void equilibrioPorTiempo() {
        assertThat(StrengthsAnalysis.balance("2do tiempo", "Dif", 2.38, 21).verdict()).isEqualTo(Verdict.FORTALEZA);
        assertThat(StrengthsAnalysis.balance("1er tiempo", "Dif", 0.2, 21).verdict()).isEqualTo(Verdict.EN_LINEA);
        assertThat(StrengthsAnalysis.balance("1er tiempo", "Dif", -0.5, 21).verdict()).isEqualTo(Verdict.DEBILIDAD);
        assertThat(StrengthsAnalysis.balance("1er tiempo", "Dif", 3, 1).verdict()).isEqualTo(Verdict.MUESTRA_CHICA);
    }

    @Test
    void dependenciaGoleadora() {
        assertThat(StrengthsAnalysis.scorerDependence(player(40), 50).verdict()).isEqualTo(Verdict.DEBILIDAD);
        assertThat(StrengthsAnalysis.scorerDependence(player(30), 50).verdict()).isEqualTo(Verdict.EN_LINEA);
        assertThat(StrengthsAnalysis.scorerDependence(player(20), 50).verdict()).isEqualTo(Verdict.FORTALEZA);
        assertThat(StrengthsAnalysis.scorerDependence(player(60), 5).verdict()).isEqualTo(Verdict.MUESTRA_CHICA);
    }

    @Test
    void elInformeTraeLaEvaluacionPorEquipo() {
        ReportData d = StatsEngine.build(StatsEngineTest.VELEZ, "Infantil Femenino", "Clausura",
                List.of(StatsEngineTest.data(765)), StatsEngineTest.HOY);
        TeamReport a = StatsEngineTest.team(d, "CAVS A");
        TeamReport b = StatsEngineTest.team(d, "CAVS B");

        assertThat(a.indicators()).extracting(Indicator::area)
                .contains("Ataque", "Defensa", "Local", "Visitante", "Forma", "Pelota parada", "Disciplina", "Goleo");
        // Vélez A: 80 a favor y 8 en contra en 9 partidos, muy por encima de la liga.
        assertThat(verdict(a, "Ataque")).isEqualTo(Verdict.FORTALEZA);
        assertThat(verdict(a, "Defensa")).isEqualTo(Verdict.FORTALEZA);
        // Vélez B: 29 a favor y 74 en contra en 10 partidos.
        assertThat(verdict(b, "Defensa")).isEqualTo(Verdict.DEBILIDAD);
        // Faltas directas: 1 intento → muestra chica.
        assertThat(a.indicators()).filteredOn(i -> i.metric().equals("Faltas directas convertidas"))
                .singleElement().satisfies(i -> assertThat(i.verdict()).isEqualTo(Verdict.MUESTRA_CHICA));
        assertThat(d.league().effectiveness()).isBetween(30.0, 50.0);
    }

    private static Verdict verdict(TeamReport t, String area) {
        return t.indicators().stream().filter(i -> i.area().equals(area)).findFirst().orElseThrow().verdict();
    }

    private static PlayerLine player(double share) {
        return new PlayerLine(1, "Jugador", 10, 10, 0, 1, share, 0, 0, 0, 0, 0, 0);
    }
}
