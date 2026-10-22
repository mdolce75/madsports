package com.madsports.account;

import com.madsports.support.PostgresContainers;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Alta de cuentas de Informes: los mismos casos de {@link ReportsSignupTest}, contra PostgreSQL real (migraciones de {@code db/migration/postgresql}). */
@Testcontainers
class ReportsSignupIT extends ReportsSignupTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = PostgresContainers.postgres();
}
