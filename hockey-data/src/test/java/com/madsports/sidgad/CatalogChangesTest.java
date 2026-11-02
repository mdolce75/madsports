package com.madsports.sidgad;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Qué cambió entre dos copias del catálogo: competiciones y equipos, no el resto del HTML. */
class CatalogChangesTest {

    private static String competition(int id, String name, String teams) {
        return "<a class='idc_master id_fede_3 temp_15' id='" + id + "'><span class='nombre_competicion_listado'>"
                + name + "</span></a><input id='teams_array_" + id + "' value='" + teams + "'>";
    }

    static final String APERTURA = "TORNEO APERTURA PRIMERA MASCULINO (FPP)";
    static final String CLAUSURA = "TORNEO CLAUSURA PRIMERA MASCULINO (FPP)";

    @Test
    void competitionsAndTeamsThatComeAndGo() {
        String before = competition(779, APERTURA, "22,101,VELEZ,VELEZ;30,102,CAVS,CAVS")
                + competition(700, "TORNEO APERTURA INFANTIL MASCULINO (FPP)", "22,201,VELEZ,VELEZ");
        String after = competition(779, APERTURA, "22,101,VELEZ,VELEZ;30,103,CAVS B,CAVS B")
                + competition(800, CLAUSURA, "22,101,VELEZ,VELEZ");

        assertThat(CatalogChanges.between(before, after)).containsExactly(
                "Competición nueva: " + CLAUSURA + " (800)",
                "Ya no está: TORNEO APERTURA INFANTIL MASCULINO (FPP) (700)",
                "En " + APERTURA + ": entra CAVS B",
                "En " + APERTURA + ": sale CAVS");
    }

    @Test
    void theRestOfTheHtmlDoesNotCount() {
        String c = competition(779, APERTURA, "22,101,VELEZ,VELEZ");
        assertThat(CatalogChanges.between("<div>12:00</div>" + c, c + "<div>13:05</div>")).isEmpty();
    }
}
