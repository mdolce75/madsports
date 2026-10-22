package com.madsports;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Las capas del proyecto, camino a los módulos de Mad Sports (ver el análisis de arquitectura):
 * <ul>
 *   <li><b>Datos de hockey</b> ({@code sidgad}, {@code catalog}, {@code stats}): SIDGAD, clubes, categorías,
 *       planteles y estadísticas. Los usan los dos productos.</li>
 *   <li><b>Plataforma</b> ({@code account}, {@code config}, {@code pdf}): cuentas, seguridad, mails, planes y el paso
 *       de plantillas a PDF.</li>
 *   <li><b>RinkReports</b> ({@code report}, {@code player}, {@code analysis}) y <b>RinkMetrics</b> ({@code coach}): cada
 *       producto usa los datos y la plataforma, nunca el otro producto.</li>
 *   <li><b>App</b> ({@code monitor} y la clase principal): lo que junta todo.</li>
 * </ul>
 */
class ArchitectureTest {

    static final String[] DATA = {"..madsports.sidgad..", "..madsports.catalog..", "..madsports.stats.."};
    static final String[] PLATFORM = {"..madsports.account..", "..madsports.config..", "..madsports.pdf.."};
    static final String[] RINKREPORTS = {"..madsports.report..", "..madsports.player..", "..madsports.analysis.."};
    static final String[] RINKMETRICS = {"..madsports.dt.."};
    static final String[] APP = {"..madsports.monitor.."};

    static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.madsports");

    @Test
    void rinkMetricsDoesNotUseRinkReports() {
        noClasses().that().resideInAnyPackage(RINKMETRICS)
                .should().dependOnClassesThat().resideInAnyPackage(RINKREPORTS)
                .check(CLASSES);
    }

    @Test
    void rinkReportsDoesNotUseRinkMetrics() {
        noClasses().that().resideInAnyPackage(RINKREPORTS)
                .should().dependOnClassesThat().resideInAnyPackage(RINKMETRICS)
                .check(CLASSES);
    }

    @Test
    void thePlatformDoesNotDependOnTheProducts() {
        noClasses().that().resideInAnyPackage(PLATFORM)
                .should().dependOnClassesThat().resideInAnyPackage(concat(RINKREPORTS, RINKMETRICS, APP))
                .check(CLASSES);
    }

    @Test
    void hockeyDataDoesNotDependOnThePlatformOrProducts() {
        noClasses().that().resideInAnyPackage(DATA)
                .should().dependOnClassesThat().resideInAnyPackage(concat(PLATFORM, RINKREPORTS, RINKMETRICS, APP))
                .check(CLASSES);
    }

    @Test
    void nobodyUsesTheApp() {
        noClasses().that().resideInAnyPackage(concat(DATA, PLATFORM, RINKREPORTS, RINKMETRICS))
                .should().dependOnClassesThat().resideInAnyPackage(APP)
                .check(CLASSES);
    }

    private static String[] concat(String[]... groups) {
        return java.util.Arrays.stream(groups).flatMap(java.util.Arrays::stream).toArray(String[]::new);
    }
}
