-- Precio (en dólares) e informes por mes de cada plan.
alter table suscripcion_type add column precio_usd decimal(10, 2);
alter table suscripcion_type add column informes_por_mes int;

update suscripcion_type set precio_usd = 30,  informes_por_mes = 8  where codigo = 'CLUB_MENSUAL';
update suscripcion_type set precio_usd = 80,  informes_por_mes = 10 where codigo = 'CLUB_TRIMESTRAL';
update suscripcion_type set precio_usd = 150, informes_por_mes = 12 where codigo = 'CLUB_SEMESTRAL';
update suscripcion_type set precio_usd = 280, informes_por_mes = 14 where codigo = 'CLUB_ANUAL';

update suscripcion_type set precio_usd = 15,  informes_por_mes = 4  where codigo = 'PLAYER_MENSUAL';
update suscripcion_type set precio_usd = 40,  informes_por_mes = 6  where codigo = 'PLAYER_TRIMESTRAL';
update suscripcion_type set precio_usd = 75,  informes_por_mes = 8  where codigo = 'PLAYER_SEMESTRAL';
update suscripcion_type set precio_usd = 140, informes_por_mes = 10 where codigo = 'PLAYER_ANUAL';

alter table suscripcion_type alter column precio_usd set not null;
alter table suscripcion_type alter column informes_por_mes set not null;
