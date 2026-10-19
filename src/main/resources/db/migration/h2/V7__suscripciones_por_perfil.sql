-- Los tipos de suscripción son por tipo de usuario: jugador (PLAYER_*) o club (CLUB_*).
alter table suscripcion_type add column tipo_usuario varchar(10);

-- Los cuatro tipos de la V6 pasan a ser los de jugador (conservan su id).
update suscripcion_type set codigo = 'PLAYER_' || codigo, nombre = 'Jugador ' || lower(nombre), tipo_usuario = 'PLAYER';

insert into suscripcion_type (codigo, nombre, meses, tipo_usuario) values ('CLUB_MENSUAL', 'Club mensual', 1, 'CLUB');
insert into suscripcion_type (codigo, nombre, meses, tipo_usuario) values ('CLUB_TRIMESTRAL', 'Club trimestral', 3, 'CLUB');
insert into suscripcion_type (codigo, nombre, meses, tipo_usuario) values ('CLUB_SEMESTRAL', 'Club semestral', 6, 'CLUB');
insert into suscripcion_type (codigo, nombre, meses, tipo_usuario) values ('CLUB_ANUAL', 'Club anual', 12, 'CLUB');

alter table suscripcion_type alter column tipo_usuario set not null;
