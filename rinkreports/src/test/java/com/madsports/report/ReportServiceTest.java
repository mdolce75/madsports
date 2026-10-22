package com.madsports.report;

import com.madsports.Fixtures;
import com.madsports.catalog.CatalogService;
import com.madsports.report.ReportService.Scope;
import com.madsports.sidgad.model.Competition;
import com.madsports.sidgad.model.Models.Match;
import com.madsports.sidgad.parse.SidgadParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReportServiceTest {

    final Competition primera = SidgadParser.catalog(Fixtures.read("catalogo.html")).stream()
            .filter(c -> c.id() == 779).findFirst().orElseThrow();
    final List<Match> matches = SidgadParser.calendar(Fixtures.read("cal_779.html"));

    @Test
    void theCrestIsTheLogoMostUsedByTheClubTeams() {
        assertThat(ReportService.logoFile(List.of(primera), 22)).isEqualTo("22_10.png");
        assertThat(ReportService.logoFile(List.of(primera), 74)).isEqualTo("74_3.png");
        assertThat(ReportService.logoFile(List.of(primera), 999)).isNull();
    }

    @Test
    void theCrestBadgeIsASquarePng() throws Exception {
        java.awt.image.BufferedImage logo = new java.awt.image.BufferedImage(40, 65, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(logo, "png", png);

        String tile = Crests.tile(png.toByteArray());
        assertThat(tile).startsWith("data:image/png;base64,");
        java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(
                java.util.Base64.getDecoder().decode(tile.substring(tile.indexOf(',') + 1))));
        assertThat(img.getWidth()).isEqualTo(img.getHeight());
        // Si la imagen no se puede leer, el informe sale sin escudo.
        assertThat(Crests.tile("no es un png".getBytes())).isNull();
        assertThat(Crests.tile(null)).isNull();
    }

    @Test
    void sheetsOfAllPlayedClubMatchesAreRead() {
        assertThat(ReportService.sheetsToRead(primera, matches, 74, 0))
                .extracting(Match::idp).containsExactly(26390, 26396);
    }

    @Test
    void withLastNOnlyThoseSheetsAreRead() {
        assertThat(ReportService.sheetsToRead(primera, matches, 74, 1))
                .extracting(Match::idp).containsExactly(26396);
    }

    @Test
    void categoryLabelWithTheTeam() {
        CatalogService.CategoryOption b = CatalogService.CategoryOption.parse("infantil:b");
        Competition.Category cat = new Competition.Category("INFANTIL", "FEMENINO");

        assertThat(b.level()).isEqualTo("INFANTIL");
        assertThat(b.teamKey("CAVS")).isEqualTo("CAVS B");
        assertThat(ReportService.label(cat, b)).isEqualTo("Infantil B Femenino");
        CatalogService.CategoryOption single = CatalogService.CategoryOption.parse("PRIMERA");
        assertThat(single.teamKey("HURA")).isEqualTo("HURA");
        assertThat(ReportService.label(new Competition.Category("PRIMERA", "MASCULINO"), single)).isEqualTo("Primera Masculino");
    }

    @Test
    void periods() {
        assertThat(Scope.parse(null)).isEqualTo(Scope.SEASON);
        assertThat(Scope.parse("last_5").lastMatches()).isEqualTo(5);
        assertThat(Scope.parse("OPENING").tournament()).isEqualTo(Competition.Tournament.OPENING);
        assertThat(Scope.SEASON.tournament()).isNull();
        assertThatThrownBy(() -> Scope.parse("PLAYOFF")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Período inválido");
    }
}
