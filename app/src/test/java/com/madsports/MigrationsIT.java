package com.madsports;

import com.madsports.catalog.CatalogService;
import com.madsports.catalog.RosterService;
import com.madsports.support.PostgresContainers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Esquema de producción: las migraciones de {@code db/migration/postgresql} se aplican completas sobre PostgreSQL real,
 * Hibernate lo valida contra las entidades (si no, el contexto no arranca) y las reglas que dependen del motor quedan
 * como en H2: fechas con zona, borrado en cascada de los partidos del equipo y número de jugador único por DT.
 */
@SpringBootTest
@Testcontainers
class MigrationsIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = PostgresContainers.postgres();

    @Autowired JdbcTemplate jdbc;
    @MockitoBean CatalogService catalog;
    @MockitoBean RosterService roster;

    static List<String> versions(String engine) throws IOException {
        Resource[] files = new PathMatchingResourcePatternResolver()
                .getResources("classpath:db/migration/" + engine + "/V*__*.sql");
        return Arrays.stream(files).map(r -> r.getFilename().substring(1, r.getFilename().indexOf("__")))
                .sorted().toList();
    }

    @Test
    void bothFoldersHaveTheSameVersionsAndAllApply() throws IOException {
        List<String> postgres = versions("postgresql");
        assertThat(postgres).isNotEmpty().containsExactlyElementsOf(versions("h2"));

        List<String> applied = jdbc.queryForList(
                "select version from flyway_schema_history where success order by installed_rank", String.class);
        assertThat(applied).containsExactlyInAnyOrderElementsOf(postgres);
    }

    @Test
    void datesKeepTheZone() {
        assertThat(jdbc.queryForList("""
                select table_name || '.' || column_name from information_schema.columns
                where table_schema = 'public' and data_type = 'timestamp without time zone'
                  and table_name <> 'flyway_schema_history'""", String.class)).isEmpty();
    }

    @Test
    void deletingATeamDeletesItsMatches() {
        assertThat(jdbc.queryForObject("""
                select rc.delete_rule from information_schema.referential_constraints rc
                join information_schema.key_column_usage k on k.constraint_name = rc.constraint_name
                where k.table_name = 'partido_dt' and k.column_name = 'equipo_id'""", String.class))
                .isEqualTo("CASCADE");
    }

    @Test
    void playerNumberIsUniquePerCoachAndNoOldColumnsRemain() {
        assertThat(jdbc.queryForObject("""
                select count(*) from information_schema.table_constraints
                where table_name = 'jugador_dt' and constraint_name = 'jugador_dt_id_unico'
                  and constraint_type = 'UNIQUE'""", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForList("""
                select table_name || '.' || column_name from information_schema.columns
                where table_schema = 'public' and table_name in ('equipo_dt', 'jugador_dt', 'partido_dt')
                  and column_name in ('categoria', 'rama')""", String.class)).isEmpty();
    }
}
