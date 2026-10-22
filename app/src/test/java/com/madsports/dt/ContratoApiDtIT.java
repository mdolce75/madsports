package com.madsports.dt;

import com.madsports.support.PostgresContainers;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** El contrato de la API de RinkMetrics ({@link ContratoApiDtTest}) contra PostgreSQL real. */
@Testcontainers
@Import(DtControllerTest.Config.class)
class ContratoApiDtIT extends ContratoApiDtTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = PostgresContainers.postgres();
}
