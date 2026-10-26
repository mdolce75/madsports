-- El mismo usuario puede tener una cuenta en cada producto (como el email desde V22): deja de ser único en toda la tabla
-- y pasa a serlo dentro del producto. Las restricciones conservan el nombre que les dio PostgreSQL antes de V24.

-- "Recordarme" guarda el nombre de la sesión, que ahora lleva el producto ("REPORTS:juan"): la columna ya no apunta al
-- usuario de user_account. Va primero: la referencia depende de la restricción única del usuario.
alter table persistent_logins drop constraint persistent_logins_username_fkey;
alter table persistent_logins alter column username type varchar(64);
update persistent_logins p set username = a.product || ':' || a.username from user_account a
    where a.username = p.username;

alter table user_account drop constraint usuario_usuario_key;
alter table user_account add constraint user_account_username_product unique (username, product);
