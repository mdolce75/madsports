package com.madsports.account;

import com.madsports.support.PostgresContainers;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Seguridad, sesión, registro, recordarme y health check: los mismos casos de {@link SecurityTest}, contra PostgreSQL real (migraciones de {@code db/migration/postgresql}). */
@Testcontainers
class SecurityIT extends SecurityTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = PostgresContainers.postgres();
}
