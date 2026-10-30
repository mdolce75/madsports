package com.madsports.psj;

import com.madsports.catalog.CatalogService;
import com.madsports.catalog.CatalogService.CatalogView;
import com.madsports.catalog.CatalogService.ClubView;
import com.madsports.catalog.CatalogService.LevelView;
import com.madsports.sidgad.SidgadProperties;
import com.madsports.sidgad.model.Competition;
import com.madsports.sidgad.model.Competition.Category;
import com.madsports.sidgad.model.Federation;
import com.madsports.source.HockeySources;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** El catálogo de la FSP desde gestiondepsj (fixtures de {@link PsjSourceTest}: Primera A1 Apertura y la Copa Nacional). */
class FspCatalogTest {

    static final SidgadProperties PROPS = new SidgadProperties("http://localhost/", "http://localhost", Duration.ZERO,
            1, Duration.ofMinutes(1), Duration.ofMinutes(1), Duration.ofSeconds(1), List.of(2026), 1);

    final PsjClient client = new PsjClient(PsjSourceTest.PROPS) {
        @Override
        protected String download(String path) {
            return PsjSourceTest.fixture(path);
        }
    };
    final CatalogService catalog = new CatalogService(HockeySources.of(new PsjSource(client)), PROPS);

    @Test
    void clubsOfTheFederationWithTheirCategories() {
        CatalogView view = catalog.view(Federation.FSP, 2026);
        assertThat(view.available()).isTrue();
        assertThat(view.federation()).isEqualTo("FSP");
        // Los clubes de los torneos de la FSP; los de otras provincias que solo juegan la Copa Nacional, no.
        assertThat(view.clubs()).hasSize(12).extracting(ClubView::name).contains("Aberastain", "UVT")
                .doesNotContain("Petroleros", "Giol");
        ClubView aberastain = view.clubs().stream().filter(c -> c.id() == 15).findFirst().orElseThrow();
        assertThat(aberastain.code()).isEqualTo("ABERASTAIN");
        assertThat(aberastain.categories()).extracting(LevelView::key, LevelView::label)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("PRIMERA A1", "Primera A1"));
        assertThat(aberastain.categories().getFirst().branches()).containsExactly("MASCULINO");
    }

    @Test
    void theClubReportOfPrimeraA1IncludesTheNationalCup() {
        Category primera = catalog.category(Federation.FSP, "primera a1", "masculino");
        List<Competition> comps = catalog.competitionsFor(Federation.FSP, 15, primera, null, 2026);
        assertThat(comps).extracting(Competition::id).containsExactly(200004, 200031);
        assertThat(comps).extracting(Competition::tournamentLabel).containsExactly("Apertura", "Copa Nacional de Clubes");
        // Solo el Apertura: la Copa no es un período que se elija.
        assertThat(catalog.competitionsFor(Federation.FSP, 15, primera, Competition.Tournament.OPENING, 2026))
                .extracting(Competition::id).containsExactly(200004);
    }

    @Test
    void federationsWithoutTheirSourceAreNotOffered() {
        // Solo con gestiondepsj: la FPP no está disponible (y no se consulta), pero el año por defecto sale igual.
        assertThat(catalog.available(Federation.FPP, 2026)).isFalse();
        assertThat(catalog.defaultYear()).isEqualTo(2026);
    }
}
