package com.madsports.report;

import com.madsports.catalog.CatalogService.CategoryOption;
import com.madsports.catalog.CatalogService.ClubView;
import com.madsports.sidgad.model.Competition.Category;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Los nombres de los archivos: RinkReports-Rama-Club-Categoria-Periodo. */
class FileNamesTest {

    @Test
    void eachPartWithoutAccentsNorSpaces() {
        assertThat(FileNames.part("Pérez Barbosa")).isEqualTo("PerezBarbosa");
        assertThat(FileNames.part("INFANTIL B")).isEqualTo("InfantilB");
        assertThat(FileNames.club("CAVS")).isEqualTo("CAVS");
        assertThat(FileNames.scope(ReportService.Scope.SEASON)).isEqualTo("Temporada");
        assertThat(FileNames.scope(ReportService.Scope.LAST_5)).isEqualTo("Ultimos5");
        assertThat(FileNames.club("CAVS B")).isEqualTo("CAVSB");
        assertThat(FileNames.of("pdf", "Femenino", null, "", "CAVS")).isEqualTo("RinkReports-Femenino-CAVS.pdf");
    }

    @Test
    void categoryReport() {
        ClubView club = new ClubView(74, "CAVS", "Vélez Sarsfield", List.of());
        assertThat(ReportService.fileName(club, new Category("PRIMERA", "FEMENINO"), new CategoryOption("PRIMERA", null),
                ReportService.Scope.OPENING)).isEqualTo("RinkReports-Femenino-CAVS-Primera-Apertura.pdf");
        assertThat(ReportService.fileName(club, new Category("INFANTIL", "MASCULINO"), new CategoryOption("INFANTIL", "B"),
                ReportService.Scope.CLOSING)).isEqualTo("RinkReports-Masculino-CAVS-InfantilB-Clausura.pdf");
    }
}
