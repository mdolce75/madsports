package com.madsports.catalog;

import com.madsports.Fixtures;
import com.madsports.catalog.CatalogService.CatalogView;
import com.madsports.catalog.CatalogService.ClubView;
import com.madsports.sidgad.model.Competition;
import com.madsports.sidgad.model.Federation;
import com.madsports.sidgad.parse.SidgadParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Catálogo 2026 con las dos federaciones: FPP (id_fede_3) y AMP de Mendoza (id_fede_4). */
class AmpCatalogTest {

    final List<Competition> all = SidgadParser.catalog(Fixtures.read("catalogo_2026.html"));

    @Test
    void ampNamesAreParsedEvenWithQuotes() {
        // El atributo name se corta en las comillas: el nombre sale del texto visible.
        assertThat(all).filteredOn(c -> c.id() == 703).singleElement().satisfies(c -> {
            assertThat(c.name()).isEqualTo("AMP'26- CAMP MASC \"APERTURA 26\"-CATEGORIA SENIOR");
            assertThat(c.federation()).isEqualTo(Federation.AMP);
            assertThat(c.tournament()).isEqualTo(Competition.Tournament.OPENING);
            assertThat(c.category()).isEqualTo(new Competition.Category("SENIOR", "MASCULINO"));
            assertThat(c.periodMinutes()).isEqualTo(25);
        });
        // Variantes del prefijo y sin guion antes de CATEGORIA.
        assertThat(all).filteredOn(c -> c.id() == 753).singleElement()
                .satisfies(c -> assertThat(c.category()).isEqualTo(new Competition.Category("PREINFANTIL", "MASCULINO")));
        assertThat(all).filteredOn(c -> c.id() == 761).singleElement()
                .satisfies(c -> assertThat(c.category()).isEqualTo(new Competition.Category("INFANTIL", "FEMENINO")));
        // Todas las de la AMP 2026 tienen torneo y categoría.
        assertThat(all).filteredOn(c -> c.federationId() == 4 && c.seasonId() == 40).hasSize(38)
                .allSatisfy(c -> assertThat(c.category()).isNotNull());
    }

    @Test
    void ampIsSurveyedFromInfantilUp() {
        List<Competition> amp = CatalogService.competitions(all, Federation.AMP.id(), Federation.AMP.levels(), 40);

        // Sin Promo, Premini, Mini ni Preinfantil: 9 categorías masculinas y 5 femeninas, Apertura y Clausura.
        assertThat(amp).hasSize(28).allSatisfy(c -> assertThat(c.federation()).isEqualTo(Federation.AMP));
        assertThat(amp).extracting(c -> c.category().level()).doesNotContain("PREINFANTIL", "MINI", "PREMINI", "PROMO");
        CatalogView view = CatalogService.view(amp, Federation.AMP.levels(), 2026, List.of(2026));
        assertThat(view.categories()).extracting(CatalogService.LevelView::key).containsExactly(
                "INFANTIL", "PRECADETE", "CADETE", "PREJUVENIL", "JUVENIL", "PREJUNIOR", "JUNIOR", "RESERVA", "SENIOR");
        // Leonardo Murialdo (club 3) tiene equipos A y B en Juvenil masculino.
        ClubView murialdo = view.clubs().stream().filter(c -> c.id() == 3).findFirst().orElseThrow();
        assertThat(murialdo.categories()).extracting(CatalogService.LevelView::key).contains("JUVENIL:A", "JUVENIL:B");
        // Los clubes de la FPP no aparecen en la AMP.
        assertThat(view.clubs()).noneMatch(c -> c.id() == 74);
    }
}
