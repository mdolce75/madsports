package com.rinkmetrics.account;

import com.rinkmetrics.support.PostgresContainers;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Alta de cuentas de Informes cerrada: los mismos casos de {@link AltaInformesCerradaTest}, contra PostgreSQL real (migraciones de {@code db/migration/postgresql}). */
@Testcontainers
class AltaInformesCerradaIT extends AltaInformesCerradaTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = PostgresContainers.postgres();
}
