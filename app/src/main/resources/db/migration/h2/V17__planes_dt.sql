-- Planes del DT: el free, 1 equipo y 5 partidos por mes; el mensual, hasta 3 equipos y 25 partidos por mes.
update suscripcion_type set equipos_max = 1, partidos_por_mes = 5 where codigo = 'DT_FREE';
update suscripcion_type set equipos_max = 3, partidos_por_mes = 25 where codigo = 'DT_MENSUAL';
