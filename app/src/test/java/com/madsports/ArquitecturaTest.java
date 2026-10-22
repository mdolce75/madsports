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
 *   <li><b>RinkReports</b> ({@code report}, {@code player}, {@code analysis}) y <b>RinkMetrics</b> ({@code dt}): cada
 *       producto usa los datos y la plataforma, nunca el otro producto.</li>
 *   <li><b>App</b> ({@code monitor} y la clase principal): lo que junta todo.</li>
 * </ul>
 */
class ArquitecturaTest {

    static final String[] DATOS = {"..madsports.sidgad..", "..madsports.catalog..", "..madsports.stats.."};
    static final String[] PLATAFORMA = {"..madsports.account..", "..madsports.config..", "..madsports.pdf.."};
    static final String[] RINKREPORTS = {"..madsports.report..", "..madsports.player..", "..madsports.analysis.."};
    static final String[] RINKMETRICS = {"..madsports.dt.."};
    static final String[] APP = {"..madsports.monitor.."};

    static final JavaClasses CLASES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.madsports");

    @Test
    void rinkMetricsNoUsaRinkReports() {
        noClasses().that().resideInAnyPackage(RINKMETRICS)
                .should().dependOnClassesThat().resideInAnyPackage(RINKREPORTS)
                .check(CLASES);
    }

    @Test
    void rinkReportsNoUsaRinkMetrics() {
        noClasses().that().resideInAnyPackage(RINKREPORTS)
                .should().dependOnClassesThat().resideInAnyPackage(RINKMETRICS)
                .check(CLASES);
    }

    @Test
    void laPlataformaNoDependeDeLosProductos() {
        noClasses().that().resideInAnyPackage(PLATAFORMA)
                .should().dependOnClassesThat().resideInAnyPackage(concat(RINKREPORTS, RINKMETRICS, APP))
                .check(CLASES);
    }

    @Test
    void losDatosDeHockeyNoDependenDeLaPlataformaNiDeLosProductos() {
        noClasses().that().resideInAnyPackage(DATOS)
                .should().dependOnClassesThat().resideInAnyPackage(concat(PLATAFORMA, RINKREPORTS, RINKMETRICS, APP))
                .check(CLASES);
    }

    @Test
    void nadieUsaLaApp() {
        noClasses().that().resideInAnyPackage(concat(DATOS, PLATAFORMA, RINKREPORTS, RINKMETRICS))
                .should().dependOnClassesThat().resideInAnyPackage(APP)
                .check(CLASES);
    }

    private static String[] concat(String[]... grupos) {
        return java.util.Arrays.stream(grupos).flatMap(java.util.Arrays::stream).toArray(String[]::new);
    }
}
