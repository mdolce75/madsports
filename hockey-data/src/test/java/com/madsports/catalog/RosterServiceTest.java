package com.madsports.catalog;

import com.madsports.Fixtures;
import com.madsports.catalog.CatalogService.ClubView;
import com.madsports.catalog.CatalogService.LevelView;
import com.madsports.sidgad.SidgadClient;
import com.madsports.sidgad.SidgadProperties;
import com.madsports.sidgad.model.Federation;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;

/** Una categoría del club se ofrece solo si tiene jugadores en SIDGAD (alguna planilla en sus actas). */
class RosterServiceTest {

    static final SidgadProperties PROPS = new SidgadProperties("http://localhost/", "http://localhost", Duration.ZERO, 1,
            Duration.ofMinutes(1), Duration.ofMinutes(1), Duration.ofSeconds(1), List.of(2026, 2027), 1);

    /** SIDGAD con las páginas guardadas; {@code sheet} decide qué trae el acta de cada partido. */
    static RosterService roster(IntFunction<String> sheet, boolean calendarFails) {
        SidgadClient client = new SidgadClient(PROPS) {
            @Override
            public String portal() {
                return Fixtures.read("portal.html");
            }

            @Override
            public String catalog() {
                return Fixtures.read("catalogo.html");
            }

            @Override
            public String calendar(int idc) {
                if (calendarFails) {
                    throw new SidgadException("SIDGAD no responde");
                }
                return Fixtures.read("cal_779.html");
            }

            @Override
            public String gameSheet(int idc, int idp) {
                return sheet.apply(idp);
            }
        };
        return new RosterService(new CatalogService(client, PROPS), client);
    }

    static final ClubView VELEZ = new ClubView(74, "CAVS", "VELEZ",
            List.of(new LevelView("PRIMERA", "Primera", List.of("MASCULINO"))));

    @Test
    void withPlayersInItsSheetsTheCategoryIsOffered() {
        RosterService r = roster(idp -> Fixtures.read("gr_26389.html"), false);
        assertThat(r.hasPlayers(Federation.FPP, 74, 2026, "PRIMERA", "MASCULINO")).isTrue();
        assertThat(r.withPlayers(Federation.FPP, VELEZ, 2026).categories()).extracting(LevelView::key)
                .containsExactly("PRIMERA");
    }

    @Test
    void withoutPublishedSheetsItIsNotOffered() {
        RosterService r = roster(idp -> SidgadClient.NOT_PUBLISHED, false);
        assertThat(r.hasPlayers(Federation.FPP, 74, 2026, "PRIMERA", "MASCULINO")).isFalse();
        assertThat(r.withPlayers(Federation.FPP, VELEZ, 2026).categories()).isEmpty();
        // Una categoría en la que el club no tiene equipo, tampoco.
        assertThat(r.hasPlayers(Federation.FPP, 74, 2026, "INFANTIL:C", "MASCULINO")).isFalse();
    }

    @Test
    void ifSidgadDoesNotAnswerItIsStillOffered() {
        RosterService r = roster(idp -> SidgadClient.NOT_PUBLISHED, true);
        assertThat(r.hasPlayers(Federation.FPP, 74, 2026, "PRIMERA", "MASCULINO")).isTrue();
    }
}
