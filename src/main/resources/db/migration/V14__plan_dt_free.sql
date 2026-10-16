-- Plan DT free: sin costo, 1 equipo y partidos sin límite (partidos_por_mes = 0), vigente hasta el 31/12/2026.
-- vigente_hasta: último día de un plan con fecha fija de fin (null = mensual, se renueva cada mes).
alter table suscripcion_type add column vigente_hasta date;
insert into suscripcion_type (codigo, nombre, tipo_usuario, precio_usd, informes_por_mes, equipos_max, partidos_por_mes,
                              vigente_hasta)
    values ('DT_FREE', 'DT free', 'DT', 0, 0, 1, 0, date '2026-12-31');
