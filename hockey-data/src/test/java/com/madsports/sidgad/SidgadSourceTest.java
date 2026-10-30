package com.madsports.sidgad;

import com.madsports.Fixtures;
import com.madsports.sidgad.model.Competition;
import com.madsports.sidgad.model.Federation;
import com.madsports.sidgad.parse.SidgadParser;
import com.madsports.source.DataSource;
import com.madsports.source.GameSheet;
import com.madsports.source.HockeySources;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SidgadSourceTest {

    final SidgadClient client = mock(SidgadClient.class);
    final SidgadSource source = new SidgadSource(client);
    final Competition primera = SidgadParser.catalog(Fixtures.read("catalogo.html")).stream()
            .filter(c -> c.id() == 779).findFirst().orElseThrow();

    @Test
    void competitionsOfTheFederationInTheSeasonOfTheYear() {
        when(client.portal()).thenReturn(Fixtures.read("portal.html"));
        when(client.catalog()).thenReturn(Fixtures.read("catalogo.html"));

        List<Competition> fpp = source.competitions(Federation.FPP, 2026);
        assertThat(fpp).isNotEmpty().allMatch(c -> c.federationId() == Federation.FPP.id())
                .extracting(Competition::id).contains(779).isSorted();
        assertThat(fpp).extracting(Competition::seasonId).containsOnly(primera.seasonId());
        assertThat(source.competitions(Federation.FPP, 1999)).isEmpty();
    }

    @Test
    void whatIsNotPublishedComesBackEmpty() {
        when(client.standings(779)).thenReturn(SidgadClient.NOT_PUBLISHED);
        when(client.calendar(779)).thenReturn(SidgadClient.NOT_PUBLISHED);
        when(client.playerStats(779)).thenReturn(SidgadClient.NOT_PUBLISHED);
        when(client.gameSheet(779, 1)).thenReturn(SidgadClient.NOT_PUBLISHED);

        assertThat(source.standings(primera)).isEmpty();
        assertThat(source.calendar(primera)).isEmpty();
        assertThat(source.playerStats(primera)).isEmpty();
        GameSheet sheet = source.gameSheet(primera, 1);
        assertThat(sheet.events()).isEmpty();
        assertThat(sheet.lineups()).isEmpty();
    }

    @Test
    void readsThePublishedPages() {
        when(client.standings(779)).thenReturn(Fixtures.read("clasif_779.html"));
        when(client.calendar(779)).thenReturn(Fixtures.read("cal_779.html"));
        when(client.gameSheet(779, 26389)).thenReturn(Fixtures.read("gr_26389.html"));

        assertThat(source.standings(primera)).hasValueSatisfying(s -> {
            assertThat(s.rows()).isNotEmpty();
            assertThat(s.date()).isNotNull();
        });
        assertThat(source.calendar(primera)).hasValueSatisfying(m -> assertThat(m).isNotEmpty());
        GameSheet sheet = source.gameSheet(primera, 26389);
        assertThat(sheet.events()).isNotEmpty();
        assertThat(sheet.lineups()).isNotEmpty();
    }

    @Test
    void eachFederationUsesItsSource() {
        HockeySources sources = HockeySources.of(source);
        assertThat(sources.of(Federation.FPP)).isSameAs(source);
        assertThat(sources.of(Federation.AMP)).isSameAs(source);
        assertThat(source.kind()).isEqualTo(DataSource.SIDGAD);
        assertThatThrownBy(() -> HockeySources.of().of(Federation.FPP)).isInstanceOf(IllegalStateException.class);
    }
}
