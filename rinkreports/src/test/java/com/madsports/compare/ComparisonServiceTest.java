package com.madsports.compare;

import com.madsports.Fixtures;
import com.madsports.catalog.CatalogService;
import com.madsports.compare.ComparisonService.Request;
import com.madsports.sidgad.SidgadClient;
import com.madsports.sidgad.SidgadProperties;
import com.madsports.sidgad.model.Federation;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Qué se puede comparar, con el catálogo guardado de SIDGAD (sin red). */
class ComparisonServiceTest {

    final ComparisonService service = service();

    static ComparisonService service() {
        SidgadProperties props = new SidgadProperties("http://localhost/", "http://localhost", Duration.ZERO, 1,
                Duration.ofMinutes(1), Duration.ofMinutes(1), Duration.ofSeconds(1), List.of(2026), 1);
        SidgadClient client = new SidgadClient(props) {
            @Override
            public String portal() {
                return Fixtures.read("portal.html");
            }

            @Override
            public String catalog() {
                return Fixtures.read("catalogo.html");
            }
        };
        return new ComparisonService(new CatalogService(client, props), client, null, null, Clock.systemUTC());
    }

    private static Request request(int clubA, String categoryA, int clubB, String categoryB) {
        return new Request(Federation.FPP, 2026, "MASCULINO", clubA, categoryA, clubB, categoryB);
    }

    @Test
    void twoTeamsOfTheSameCategory() {
        // Vélez (74) y Comunicaciones (69) en Primera Masculino.
        assertThat(service.validate(request(74, "PRIMERA", 69, "PRIMERA")))
                .extracting(t -> t.club().id()).containsExactly(74, 69);
    }

    @Test
    void theSameCategoryIsRequired() {
        assertThatThrownBy(() -> service.validate(request(74, "PRIMERA", 69, "RESERVA")))
                .hasMessage("Los dos equipos tienen que ser de la misma categoría.");
    }

    @Test
    void notTheSameTeamTwice() {
        assertThatThrownBy(() -> service.validate(request(74, "PRIMERA", 74, "PRIMERA")))
                .hasMessage("Elegí dos equipos distintos.");
    }

    @Test
    void aClubWithoutThatTeamIsRejected() {
        // Vélez no tiene equipo B en Primera.
        assertThatThrownBy(() -> service.validate(request(74, "PRIMERA:B", 69, "PRIMERA")))
                .hasMessageContaining("no tiene equipo en Primera B Masculino");
    }
}
