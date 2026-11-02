-- Las copias guardadas de la fuente ya no son solo el catálogo: también las tablas, los calendarios, las
-- estadísticas por jugador y las fichas de temporada de SIDGAD (JdbcSnapshotStore). Los informes leen de acá y SIDGAD se
-- consulta en la sincronización programada. changed_at: cuándo cambió el contenido por última vez.
alter table source_catalog rename to source_snapshot;
alter table source_snapshot alter column catalog_key rename to snapshot_key;
alter table source_snapshot add column changed_at timestamp with time zone;
update source_snapshot set changed_at = fetched_at;
alter table source_snapshot alter column changed_at set not null;
