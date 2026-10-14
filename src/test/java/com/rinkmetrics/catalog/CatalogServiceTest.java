package com.rinkmetrics.catalog;

import com.rinkmetrics.Fixtures;
import com.rinkmetrics.config.FppProperties;
import com.rinkmetrics.sidgad.SidgadClient;
import com.rinkmetrics.catalog.CatalogService.CatalogView;
import com.rinkmetrics.catalog.CatalogService.ClubView;
import com.rinkmetrics.catalog.CatalogService.LevelView;
import com.rinkmetrics.sidgad.model.Competition;
import com.rinkmetrics.sidgad.parse.SidgadParser;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CatalogServiceTest {

    static final List<String> LEVELS = List.of("INFANTIL", "PREJUVENIL", "JUVENIL", "JUNIORS", "RESERVA", "PRIMERA");

    /** FPP 2026 = temporada 40 en SIDGAD (selector del portal). */
    final List<Competition> active = CatalogService.competitions(
            SidgadParser.catalog(Fixtures.read("catalogo.html")), 3, LEVELS, 40);

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
        CatalogView view = CatalogService.view(active, LEVELS, 2026, List.of(2026, 2027));

        assertThat(view.categories()).extracting(LevelView::key).containsExactlyElementsOf(LEVELS);
        assertThat(view.categories()).filteredOn(l -> l.key().equals("RESERVA"))
                .singleElement().satisfies(l -> assertThat(l.branches()).containsExactly("MASCULINO"));
        assertThat(view.categories()).filteredOn(l -> l.key().equals("INFANTIL"))
                .singleElement().satisfies(l -> assertThat(l.branches()).containsExactly("MASCULINO", "FEMENINO"));
        assertThat(view.branches()).containsExactly("MASCULINO", "FEMENINO");
    }

    @Test
    void directorioDeClubesUsaElCodigoYNombreMasFrecuentes() {
        CatalogView view = CatalogService.view(active, LEVELS, 2026, List.of(2026, 2027));

        ClubView velez = view.clubs().stream().filter(c -> c.id() == 74).findFirst().orElseThrow();
        assertThat(velez.code()).isEqualTo("CAVS");
        assertThat(velez.name()).isEqualTo("VELEZ");
        assertThat(velez.categories()).extracting(LevelView::key).doesNotContain("MINI", "PROMO").contains("PRIMERA");
        assertThat(view.clubs()).filteredOn(c -> c.id() == 74).hasSize(1);
        assertThat(view.clubs()).extracting(ClubView::name).isSorted();
    }

    /** Cliente con las páginas guardadas del portal y del catálogo (sin red). */
    static CatalogService serviceWithFixtures() {
        FppProperties props = new FppProperties(
                new FppProperties.Sidgad("http://localhost/", "http://localhost", 3,
                        Duration.ZERO, 1, Duration.ofMinutes(1), Duration.ofMinutes(1),
                        Duration.ofSeconds(1), LEVELS, List.of(2026, 2027)),
                new FppProperties.Ai(false, null, "m", "http://localhost", Duration.ofSeconds(1)));
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
        return new CatalogService(client, props);
    }

    @Test
    void conEquipoAYBLaCategoriaSeOfreceComoInfantilAEInfantilB() {
        ClubView velez = CatalogService.view(active, LEVELS, 2026, List.of(2026, 2027)).clubs().stream()
                .filter(c -> c.id() == 74).findFirst().orElseThrow();

        // Infantil: A y B en Femenino (Apertura y Clausura) y en Masculino.
        assertThat(velez.categories()).extracting(LevelView::key)
                .containsSubsequence("INFANTIL:A", "INFANTIL:B", "PREJUVENIL");
        assertThat(velez.categories()).filteredOn(l -> l.key().equals("INFANTIL:B")).singleElement()
                .satisfies(l -> {
                    assertThat(l.label()).isEqualTo("Infantil B");
                    assertThat(l.branches()).contains("FEMENINO");
                });
        // Prejuvenil: un solo equipo en cada rama (aunque en el Apertura femenino se llame "VELEZ A").
        assertThat(velez.categories()).filteredOn(l -> l.key().startsWith("PREJUVENIL"))
                .extracting(LevelView::key).containsExactly("PREJUVENIL");
        // Juniors Masculino Clausura: Comunicaciones tiene el logo de Vélez en SIDGAD, pero no es de Vélez.
        assertThat(velez.categories()).filteredOn(l -> l.key().startsWith("JUNIORS"))
                .extracting(LevelView::key).containsExactly("JUNIORS");
    }

    @Test
    void elAnioSeTraduceALaTemporadaDeSidgad() {
        assertThat(SidgadParser.seasons(Fixtures.read("portal.html")))
                .containsEntry(2026, 40).containsEntry(2025, 38).containsEntry(2022, 31)
                .doesNotContainKey(2027);
    }

    @Test
    void anioConDatos() {
        CatalogService catalog = serviceWithFixtures();
        CatalogView v2026 = catalog.view(2026);

        assertThat(v2026.available()).isTrue();
        assertThat(v2026.year()).isEqualTo(2026);
        assertThat(v2026.years()).containsExactly(2026, 2027);
        assertThat(v2026.clubs()).isNotEmpty();
        assertThat(catalog.defaultYear()).isEqualTo(2026);
    }

    @Test
    void anioSinDatosSeInforma() {
        CatalogService catalog = serviceWithFixtures();
        CatalogView v2027 = catalog.view(2027);

        assertThat(v2027.available()).isFalse();
        assertThat(v2027.message()).isEqualTo("No hay información de la FPP para el año 2027 todavía.");
        assertThat(v2027.clubs()).isEmpty();
        assertThat(catalog.competitionsFor(74, new Competition.Category("PRIMERA", "MASCULINO"), null, 2027)).isEmpty();
        assertThatThrownBy(() -> catalog.club(74, 2027))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("2027");
    }

    @Test
    void anioNoOfrecido() {
        CatalogService catalog = serviceWithFixtures();
        assertThat(catalog.checkYear(null)).isEqualTo(2026);
        assertThat(catalog.checkYear(2027)).isEqualTo(2027);
        assertThatThrownBy(() -> catalog.checkYear(2019))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Año no ofrecido");
    }
}
