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
}
