package com.madsports.coach;

import com.madsports.support.PostgresContainers;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** RinkMetrics completo (equipos, planteles, partidos, acciones, cupos, borrado en cascada): los mismos casos de {@link CoachControllerTest}, contra PostgreSQL real (migraciones de {@code db/migration/postgresql}). */
@Testcontainers
// La @TestConfiguration anidada de la clase base no se hereda: se importa (reloj de prueba).
@Import(CoachControllerTest.Config.class)
class CoachControllerIT extends CoachControllerTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = PostgresContainers.postgres();
}
