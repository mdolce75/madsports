-- "Recordarme": tokens de inicio de sesión persistente (la tabla que usa JdbcTokenRepositoryImpl de Spring Security).
-- Cada dispositivo recordado es una serie; el token cambia en cada uso. Si se borra el usuario, se borran sus tokens
-- (así un usuario nuevo con el mismo nombre no hereda dispositivos recordados).
create table persistent_logins (
    username  varchar(30) not null references usuario (usuario) on delete cascade,
    series    varchar(64) primary key,
    token     varchar(64) not null,
    last_used timestamp   not null
);
create index persistent_logins_usuario on persistent_logins (username);
