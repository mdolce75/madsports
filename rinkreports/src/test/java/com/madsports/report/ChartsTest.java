package com.madsports.report;

import com.madsports.report.ChartForms.Slice;
import com.madsports.stats.ReportData.PlayerLine;
import com.madsports.stats.ReportData.TeamRecord;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChartsTest {

    final Charts charts = new Charts();

    static PlayerLine scorer(int id, String name, int goals) {
        return new PlayerLine(id, name, 10, goals, goals / 10.0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    @Test
    void withoutDataThereIsNoChart() {
        assertThat(charts.resultsDonut(TeamRecord.EMPTY)).isNull();
        assertThat(charts.resultsDonut(null)).isNull();
        assertThat(charts.cardsDonut(0, 0, 0)).isNull();
        assertThat(charts.scorersDonut(List.of(scorer(1, "Sin Goles", 0)))).isNull();
        assertThat(charts.setPieceDonut(0, 0, "penales")).isNull();
        assertThat(ChartForms.donut(List.of(new Slice("Nada", 0, "0", ChartCanvas.OTHER)), "0", "goles")).isNull();
    }

    @Test
    void withDataEachChartIsAPng() throws IOException {
        assertThat(ChartCanvasTest.decode(charts.resultsDonut(new TeamRecord(5, 3, 1, 1, 12, 6)))).isNotNull();
        assertThat(ChartCanvasTest.decode(charts.cardsDonut(1, 2, 0))).isNotNull();
        assertThat(ChartCanvasTest.decode(charts.setPieceDonut(1, 2, "penales"))).isNotNull();
        // Más de 5 goleadores: los 5 primeros y el resto en "Otros".
        List<PlayerLine> seven = List.of(scorer(1, "A Uno", 7), scorer(2, "B Dos", 6), scorer(3, "C Tres", 5),
                scorer(4, "D Cuatro", 4), scorer(5, "E Cinco", 3), scorer(6, "F Seis", 2), scorer(7, "G Siete", 1));
        assertThat(ChartCanvasTest.decode(charts.scorersDonut(seven))).isNotNull();
    }

    @Test
    void stackedColumnsByBucket() throws IOException {
        String png = ChartForms.stackedColumns(List.of("1T 0-5'", "1T 5-10'", "2T 0-5'", "2T 5-10'"),
                new int[][]{{1, 0, 2, 0}, {0, 1, 0, 0}}, new java.awt.Color[]{ChartCanvas.CARD_YELLOW, ChartCanvas.CARD_BLUE},
                new String[]{"Amarillas", "Azules"});
        assertThat(ChartCanvasTest.decode(png).getWidth()).isEqualTo(3 * 520);
    }
}
