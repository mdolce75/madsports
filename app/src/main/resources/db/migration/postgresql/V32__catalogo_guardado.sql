-- Copia del catálogo de la fuente guardada (JdbcCatalogStore): competiciones con sus clubes y equipos, y las
-- temporadas. Casi no cambia, así que se baja una vez por día (la precarga) y no en cada reinicio; si la fuente falla,
-- se sigue con esta copia. Una fila por parte ("sidgad:ls", "sidgad:portal"), comprimida (gzip).
create table source_catalog (
    catalog_key varchar(100) primary key,
    body_gz     bytea not null,
    fetched_at  timestamp with time zone not null
);
