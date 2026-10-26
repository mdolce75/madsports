-- Actas de partido de SIDGAD guardadas (JdbcSheetStore): un partido jugado no cambia, así que cada acta se baja una
-- vez y la usan todos los informes, también después de reiniciar la app. Comprimidas (gzip), unos 20 KB cada una.
create table sidgad_game_sheet (
    idp              integer primary key,
    html_gz          bytea not null,
    first_fetched_at timestamp with time zone not null,
    fetched_at       timestamp with time zone not null
);
