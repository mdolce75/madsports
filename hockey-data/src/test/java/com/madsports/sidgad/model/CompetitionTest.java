package com.madsports.sidgad.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CompetitionTest {

    static Competition of(int id, String name) {
        return Competition.of(id, name, 1, 1, List.of(), 25);
    }

    /** Con dos Aperturas en la categoría (el 2 al final del nombre), cada una con su número; si no, sin número. */
    @Test
    void tournamentsWithTheirNumberWhenThereAreTwo() {
        Competition a1 = of(696, "TORNEO APERTURA JUNIORS FEMENINO (FPP)");
        Competition a2 = of(738, "TORNEO APERTURA JUNIORS FEMENINO 2 (FPP)");
        Competition cl = of(769, "TORNEO CLAUSURA JUNIORS FEMENINO (FPP)");
        Competition otra = of(697, "TORNEO APERTURA PRIMERA FEMENINO (FPP)");
        List<Competition> all = List.of(a1, a2, cl, otra);

        assertThat(a2.division()).isEqualTo(2);
        assertThat(a1.tournamentLabel(all)).isEqualTo("Apertura 1");
        assertThat(a2.tournamentLabel(all)).isEqualTo("Apertura 2");
        assertThat(cl.tournamentLabel(all)).isEqualTo("Clausura");
        assertThat(otra.tournamentLabel(all)).isEqualTo("Apertura");
        // Sola, la segunda igual lleva su número; la primera, no.
        assertThat(a2.tournamentLabel()).isEqualTo("Apertura 2");
        assertThat(a1.tournamentLabel()).isEqualTo("Apertura");
    }

    /** Los 17 torneos 2026 de la FSP: la división va en el nivel; el amistoso no tiene categoría. */
    @Test
    void sanJuanNames() {
        assertThat(of(200004, "A1 PRIMERA TORNEO APERTURA 2026").category()).isEqualTo(
                new Competition.Category("PRIMERA A1", "MASCULINO"));
        assertThat(of(200043, "A1 SEGUNDA TORNEO CLAUSURA 2026").category()).isEqualTo(
                new Competition.Category("SEGUNDA A1", "MASCULINO"));
        Competition a2 = of(200000, "A2  PRIMERA TORNEO APERTURA 2026");
        assertThat(a2.category()).isEqualTo(new Competition.Category("PRIMERA A2", "MASCULINO"));
        assertThat(a2.tournament()).isEqualTo(Competition.Tournament.OPENING);
        assertThat(a2.category().label()).isEqualTo("Primera A2 Masculino");
        assertThat(of(200049, "A3 SEGUNDA TORNEO CLAUSURA 2026").category().levelLabel()).isEqualTo("Segunda A3");
        Competition women = of(200038, "FEMENINO SEGUNDA TORNEO CLAUSURA 2026");
        assertThat(women.category()).isEqualTo(new Competition.Category("SEGUNDA", "FEMENINO"));
        assertThat(women.tournament()).isEqualTo(Competition.Tournament.CLOSING);
        assertThat(of(200063, "AMISTOSO INTERNACIONAL ").category()).isNull();
        // Las 16 de liga entran en las categorías relevadas de la FSP.
        assertThat(Federation.FSP.levels()).contains("PRIMERA A1", "SEGUNDA A1", "PRIMERA A2", "SEGUNDA A2",
                "PRIMERA A3", "SEGUNDA A3", "PRIMERA", "SEGUNDA");
        assertThat(Federation.FSP.allowed("SEGUNDA A1")).containsExactly("SEGUNDA A1", "PRIMERA A1");
    }

    /** Una copa se muestra con su nombre; un nivel de una palabra sigue igual ("Infantil"). */
    @Test
    void cupsWithTheirName() {
        Competition cup = Competition.cup(200031, "COPA NACIONAL DE CLUBES", 1, 2026,
                new Competition.Category("PRIMERA A1", "MASCULINO"), List.of(), 0);
        assertThat(cup.tournament()).isEqualTo(Competition.Tournament.CUP);
        assertThat(cup.tournamentLabel()).isEqualTo("Copa Nacional de Clubes");
        assertThat(cup.periodMinutes()).isEqualTo(Competition.DEFAULT_PERIOD_MINUTES);
        assertThat(new Competition.Category("INFANTIL", "FEMENINO").label()).isEqualTo("Infantil Femenino");
        assertThat(Competition.Tournament.fromSidgad("anual")).isEqualTo(Competition.Tournament.ANNUAL);
    }
}
