package com.rinkmetrics.support;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * PostgreSQL real para los tests de integración ({@code *IT}), la misma versión que producción. Cada clase IT declara
 * su contenedor con {@link #postgres()} ({@code @Container @ServiceConnection static}): así cada una arranca con la base
 * vacía y el {@code spring.datasource.url} de H2 de la clase base queda reemplazado por el del contenedor.
 */
public final class PostgresContainers {

    public static final String IMAGEN = "postgres:16-alpine";

    private PostgresContainers() {
    }

    /** Contenedor nuevo; la clase IT lo anota con {@code @Container} y {@link ServiceConnection}. */
    public static PostgreSQLContainer<?> postgres() {
        return new PostgreSQLContainer<>(IMAGEN);
    }
}
