package com.rinkmetrics;

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
 * Pendiente para los próximos pasos: los datos de hockey todavía leen su configuración de {@code config}
 * ({@code RinkMetricsProperties}).
 */
class ArquitecturaTest {

    static final String[] DATOS = {"..rinkmetrics.sidgad..", "..rinkmetrics.catalog..", "..rinkmetrics.stats.."};
    static final String[] PLATAFORMA = {"..rinkmetrics.account..", "..rinkmetrics.config..", "..rinkmetrics.pdf.."};
    static final String[] RINKREPORTS = {"..rinkmetrics.report..", "..rinkmetrics.player..", "..rinkmetrics.analysis.."};
    static final String[] RINKMETRICS = {"..rinkmetrics.dt.."};
    static final String[] APP = {"..rinkmetrics.monitor.."};

    static final JavaClasses CLASES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.rinkmetrics");

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
        // config queda permitido por ahora (sus propiedades); el resto de la plataforma, no.
        noClasses().that().resideInAnyPackage(DATOS)
                .should().dependOnClassesThat().resideInAnyPackage(
                        concat(new String[] {"..rinkmetrics.account..", "..rinkmetrics.pdf.."}, RINKREPORTS, RINKMETRICS, APP))
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
