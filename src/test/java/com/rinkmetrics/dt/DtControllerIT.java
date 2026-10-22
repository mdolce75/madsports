package com.rinkmetrics.dt;

import com.rinkmetrics.support.PostgresContainers;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** RinkMetrics completo (equipos, planteles, partidos, acciones, cupos, borrado en cascada): los mismos casos de {@link DtControllerTest}, contra PostgreSQL real (migraciones de {@code db/migration/postgresql}). */
@Testcontainers
// La @TestConfiguration anidada de la clase base no se hereda: se importa (reloj de prueba).
@Import(DtControllerTest.Config.class)
class DtControllerIT extends DtControllerTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = PostgresContainers.postgres();
}
