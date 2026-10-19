-- Solo hay planes mensuales: quien tenía uno de 3, 6 o 12 meses pasa al mensual de su tipo de usuario (conserva su
-- vigencia), y los demás planes se borran. El jugador mensual pasa a 7 dólares.
update usuario u set suscripcion_type_id = (
    select m.id from suscripcion_type m
    where m.meses = 1 and m.tipo_usuario = (select t.tipo_usuario from suscripcion_type t where t.id = u.suscripcion_type_id))
where suscripcion_type_id in (select id from suscripcion_type where meses <> 1);

delete from suscripcion_type where meses <> 1;

update suscripcion_type set precio_usd = 7 where codigo = 'PLAYER_MENSUAL';

-- Todos los planes duran un mes: la duración ya no es un dato del plan.
alter table suscripcion_type drop column meses;
