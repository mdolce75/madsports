package com.madsports.coach;

import com.madsports.support.PostgresContainers;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** El contrato de la API de RinkMetrics ({@link CoachApiContractTest}) contra PostgreSQL real. */
@Testcontainers
@Import(CoachControllerTest.Config.class)
class CoachApiContractIT extends CoachApiContractTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = PostgresContainers.postgres();
}
