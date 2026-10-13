package com.fppstats.catalog;

import com.fppstats.Fixtures;
import com.fppstats.catalog.CatalogService.CatalogView;
import com.fppstats.catalog.CatalogService.ClubView;
import com.fppstats.catalog.CatalogService.LevelView;
import com.fppstats.sidgad.model.Competition;
import com.fppstats.sidgad.parse.SidgadParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogServiceTest {

    static final List<String> LEVELS = List.of("INFANTIL", "PREJUVENIL", "JUVENIL", "JUNIORS", "RESERVA", "PRIMERA");

    final List<Competition> active = CatalogService.activeCompetitions(
            SidgadParser.catalog(Fixtures.read("catalogo.html")), 3, LEVELS);

    @Test
    void soloFppTemporadaActivaDeInfantilParaArriba() {
        // 32 competiciones FPP 2026, menos Mini y Promo (2 torneos x 2 ramas x 2 niveles = 8).
        assertThat(active).hasSize(24)
                .allSatisfy(c -> {
                    assertThat(c.federationId()).isEqualTo(3);
                    assertThat(c.seasonId()).isEqualTo(40);
                    assertThat(LEVELS).contains(c.category().level());
                });
    }

    @Test
    void categoriasEnOrdenConSusRamas() {
        CatalogView view = CatalogService.view(active, LEVELS);

        assertThat(view.categories()).extracting(LevelView::key).containsExactlyElementsOf(LEVELS);
        assertThat(view.categories()).filteredOn(l -> l.key().equals("RESERVA"))
                .singleElement().satisfies(l -> assertThat(l.branches()).containsExactly("MASCULINO"));
        assertThat(view.categories()).filteredOn(l -> l.key().equals("INFANTIL"))
                .singleElement().satisfies(l -> assertThat(l.branches()).containsExactly("MASCULINO", "FEMENINO"));
        assertThat(view.branches()).containsExactly("MASCULINO", "FEMENINO");
    }

    @Test
    void directorioDeClubesUsaElCodigoYNombreMasFrecuentes() {
        CatalogView view = CatalogService.view(active, LEVELS);

        ClubView velez = view.clubs().stream().filter(c -> c.id() == 74).findFirst().orElseThrow();
        assertThat(velez.code()).isEqualTo("CAVS");
        assertThat(velez.name()).isEqualTo("VELEZ");
        assertThat(velez.categories()).extracting(LevelView::key).doesNotContain("MINI", "PROMO").contains("PRIMERA");
        assertThat(view.clubs()).extracting(ClubView::name).isSorted();
    }
}
