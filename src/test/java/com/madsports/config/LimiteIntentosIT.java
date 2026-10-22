package com.madsports.config;

import com.madsports.support.PostgresContainers;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Límite de intentos: los mismos casos de {@link LimiteIntentosTest}, contra PostgreSQL real (migraciones de {@code db/migration/postgresql}). */
@Testcontainers
class LimiteIntentosIT extends LimiteIntentosTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = PostgresContainers.postgres();
}
