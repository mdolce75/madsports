-- Plan Jugador free: sin costo, hasta el 31/12/2026 (como el DT free), con los mismos informes por mes que el mensual.
-- El mensual (pago) figura como "Próximamente" hasta que se habilite el cobro (rinkmetrics.registro.jugador-pago).
insert into suscripcion_type (codigo, nombre, tipo_usuario, precio_usd, informes_por_mes, equipos_max, partidos_por_mes,
                              vigente_hasta)
    values ('PLAYER_FREE', 'Jugador free', 'PLAYER', 0, 4, 0, 0, date '2026-12-31');
