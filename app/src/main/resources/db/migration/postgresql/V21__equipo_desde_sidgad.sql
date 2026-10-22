-- Equipos armados desde SIDGAD: la categoría del club que eligió el DT ("PRIMERA" o "INFANTIL:B") y su rama. Null en
-- los equipos de nombre libre.
alter table equipo_dt add column categoria_sidgad varchar(30);
alter table equipo_dt add column rama_sidgad varchar(12);

-- Jugadores del plantel de SIDGAD que el DT no incluyó en el equipo: figuran en su lista como "no incluidos" y los
-- puede sumar después. No aparecen para cargar acciones (esos son los de jugador_equipo_dt).
create table jugador_pendiente_dt (
    jugador_dt_id bigint not null references jugador_dt (id) on delete cascade,
    equipo_id     bigint not null references equipo_dt (id) on delete cascade,
    primary key (jugador_dt_id, equipo_id)
);
