package com.rinkmetrics.stats;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ShortHandedCalcTest {

    static final LocalDate DIA = LocalDate.of(2026, 5, 1);
    static final int PARTIDO = 50 * 60;

    @Test
    void laSancionSigueAunqueElRivalHagaGol() {
        ShortHandedCalc calc = new ShortHandedCalc();
        // Azul al 10:00: 10:00-12:00. El rival hace dos goles en ese lapso (10:30 y 11:50) y otro al 12:00 (ya completo).
        calc.add(DIA, "RIVAL", true, List.of(new int[]{600, 720}), 1, 0,
                List.of(1500), List.of(630, 710, 720, 2000), PARTIDO, 1, 4);
        ReportData.ShortHanded sh = calc.result();

        assertThat(sh.matches()).singleElement().satisfies(m -> {
            assertThat(m.sanctions()).isEqualTo("1 azul");
            assertThat(m.secondsShort()).isEqualTo(120);
            assertThat(m.goalsAgainst()).isEqualTo(2);
            assertThat(m.goalsFor()).isZero();
            assertThat(m.result()).isEqualTo("P");
        });
        assertThat(sh.otherSeconds()).isEqualTo(PARTIDO - 120);
        assertThat(sh.otherGoalsAgainst()).isEqualTo(2);
        assertThat(sh.againstPer10Short()).isEqualTo(10.0);
        assertThat(sh.lost()).isEqualTo(1);
    }

    @Test
    void sancionesSuperpuestasYCorteAlFinal() {
        ShortHandedCalc calc = new ShortHandedCalc();
        // Dos azules a la vez (se suman 4' de sanción, pero el tiempo con uno menos es 2') y una roja al final.
        calc.add(DIA, "RIVAL", false, List.of(new int[]{600, 720}, new int[]{600, 720}, new int[]{2900, 3140}), 2, 1,
                List.of(650), List.of(700, 2950), PARTIDO, 3, 3);
        ReportData.ShortHandedMatch m = calc.result().matches().getFirst();

        assertThat(m.sanctions()).isEqualTo("2 azules y 1 roja");
        assertThat(m.secondsShort()).isEqualTo(120 + 120 + 100);   // la roja se corta al final del partido
        assertThat(m.goalsAgainst()).isEqualTo(2);
        assertThat(m.goalsFor()).isEqualTo(1);
        assertThat(m.result()).isEqualTo("E");
    }

    @Test
    void sinSancionesSoloSumaElTiempoCompleto() {
        ShortHandedCalc calc = new ShortHandedCalc();
        calc.add(DIA, "RIVAL", true, List.of(), 0, 0, List.of(100), List.of(200, 300), PARTIDO, 1, 2);
        ReportData.ShortHanded sh = calc.result();
        assertThat(sh.hasData()).isFalse();
        assertThat(sh.otherGoalsAgainst()).isEqualTo(2);
        assertThat(sh.againstPer10Rest()).isEqualTo(2 * 600.0 / PARTIDO);
    }
}
