-- Perfil DT (planilla en vivo): su plan, con cupo de equipos y de partidos por mes, y los equipos de cada DT.
alter table suscripcion_type add column equipos_max int default 0 not null;
alter table suscripcion_type add column partidos_por_mes int default 0 not null;

-- Plan DT mensual: 12 dólares, hasta 2 equipos y 20 partidos por mes.
insert into suscripcion_type (codigo, nombre, meses, tipo_usuario, precio_usd, informes_por_mes, equipos_max, partidos_por_mes)
    values ('DT_MENSUAL', 'DT mensual', 1, 'DT', 12, 0, 2, 20);

-- Equipos del DT: categoría con la letra del equipo ("JUVENIL:A") y rama.
create table usuario_equipo_dt (
    usuario_id bigint      not null references usuario (id) on delete cascade,
    categoria  varchar(30) not null,
    rama       varchar(12) not null,
    orden      int         not null,
    primary key (usuario_id, orden),
    unique (usuario_id, categoria, rama)
);
