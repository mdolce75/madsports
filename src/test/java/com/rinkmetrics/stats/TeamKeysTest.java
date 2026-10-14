package com.rinkmetrics.stats;

import com.rinkmetrics.sidgad.model.Models.TeamRef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Casos reales del catálogo 2026 de SIDGAD. */
class TeamKeysTest {

    static TeamRef team(int id, String code, String name) {
        return new TeamRef(id, 74, code, name);
    }

    @Test
    void letraEnElCodigo() {
        assertThat(TeamKeys.keys(List.of(team(1, "CAVS A", "VELEZ A"), team(2, "CAVS B", "VELEZ B")), "CAVS").values())
                .containsExactly("CAVS A", "CAVS B");
        // Pegada: "CAVSB".
        assertThat(TeamKeys.keys(List.of(team(1, "CAVS", "VELEZ SARSFIELD"), team(2, "CAVSB", "VELEZ B")), "CAVS"))
                .containsEntry(1, "CAVS A").containsEntry(2, "CAVS B");
    }

    @Test
    void mismoCodigoYLetraEnElNombreNoSeAgrupan() {
        // Mini Femenino Apertura (691): los dos equipos con código "CAVS".
        assertThat(TeamKeys.keys(List.of(team(4864, "CAVS", "VELEZ A"), team(4865, "CAVS", "VELEZ B")), "CAVS"))
                .containsEntry(4864, "CAVS A").containsEntry(4865, "CAVS B");
        // Sin letra en ningún lado: igual reciben letras distintas.
        assertThat(TeamKeys.keys(List.of(team(1, "CAVS", "VELEZ"), team(2, "CAVS", "VELEZ")), "CAVS").values())
                .containsExactly("CAVS A", "CAVS B");
    }

    @Test
    void elQueNoTieneLetraEsElA() {
        assertThat(TeamKeys.keys(List.of(team(4671, "CAVS", "VELEZ"), team(4670, "CAVS B", "VELEZ B")), "CAVS"))
                .containsEntry(4671, "CAVS A").containsEntry(4670, "CAVS B");
        assertThat(TeamKeys.keys(List.of(team(1, "CAVS B", "VELEZ SARSFIELD B")), "CAVS", true)).containsEntry(1, "CAVS B");
    }

    @Test
    void equipoUnicoEnTodoElAnioVaSinLetraAunqueSeLlameA() {
        // Prejuvenil Femenino: "VELEZ A" en el Apertura (694) y "VELEZ" en el Clausura (766), único equipo.
        assertThat(TeamKeys.keys(List.of(team(4877, "CAVS", "VELEZ A")), "CAVS", false)).containsEntry(4877, "CAVS");
        // Si en algún torneo hay dos equipos, el único de otro torneo conserva su letra (o recibe la A).
        assertThat(TeamKeys.keys(List.of(team(1, "CAVS B", "VELEZ B")), "CAVS", true)).containsEntry(1, "CAVS B");
        assertThat(TeamKeys.keys(List.of(team(1, "CAVS", "VELEZ")), "CAVS", true)).containsEntry(1, "CAVS A");
    }

    @Test
    void equipoUnicoYCodigoQueCambiaEntreTorneos() {
        assertThat(TeamKeys.keys(List.of(team(1, "CAVS", "VELEZ")), "CAVS")).containsEntry(1, "CAVS");
        // Huracán: "HUR" en el Apertura, "HURA" en el Clausura.
        assertThat(TeamKeys.keys(List.of(new TeamRef(4806, 22, "HUR", "HURACAN")), "HURA")).containsEntry(4806, "HURA");
        assertThat(TeamKeys.belongs(new TeamRef(4806, 22, "HUR", "HURACAN"), "HURA", "HURACAN")).isTrue();
    }

    @Test
    void unEquipoDeOtroClubConElLogoEquivocadoNoEsDelClub() {
        // Juniors Masculino Clausura (777): Comunicaciones figura con el logo de Vélez.
        assertThat(TeamKeys.belongs(team(5562, "COMU", "COMUNICACIONES"), "CAVS", "VELEZ")).isFalse();
        assertThat(TeamKeys.belongs(team(5563, "CAVS", "VELEZ"), "CAVS", "VELEZ")).isTrue();
        assertThat(TeamKeys.belongs(team(1, "CAVS B", "VELEZ SARSFIELD B"), "CAVS", "VELEZ")).isTrue();
    }

    @Test
    void nombreParaMostrar() {
        assertThat(TeamKeys.displayName("VELEZ", "CAVS", "CAVS B")).isEqualTo("VELEZ B");
        assertThat(TeamKeys.displayName("VELEZ", "CAVS", "CAVS")).isEqualTo("VELEZ");
    }
}
