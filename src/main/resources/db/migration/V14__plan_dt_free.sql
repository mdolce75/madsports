-- Plan DT free: sin costo, 1 equipo y 6 partidos por mes (el mes cuenta desde el alta, como en el plan mensual).
insert into suscripcion_type (codigo, nombre, tipo_usuario, precio_usd, informes_por_mes, equipos_max, partidos_por_mes)
    values ('DT_FREE', 'DT free', 'DT', 0, 0, 1, 6);
