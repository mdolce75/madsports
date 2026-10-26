-- Historial de informes generados: qué informe fue cada pedido ("Club completo · Apertura + Clausura 2026"). Los
-- de antes quedan sin detalle (solo la fecha y la hora).
alter table requested_report add column detail varchar(200);
