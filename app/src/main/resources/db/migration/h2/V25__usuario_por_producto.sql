-- El mismo usuario puede tener una cuenta en cada producto (como el email desde V22): deja de ser único en toda la tabla
-- y pasa a serlo dentro del producto.

-- "Recordarme" guarda el nombre de la sesión, que ahora lleva el producto ("REPORTS:juan"): la columna ya no apunta al
-- usuario de user_account. Va primero: la referencia depende de la restricción única del usuario.
execute immediate 'alter table persistent_logins drop constraint ' || (
    select constraint_name from information_schema.table_constraints
    where table_name = 'PERSISTENT_LOGINS' and constraint_type = 'FOREIGN KEY');
alter table persistent_logins alter column username varchar(64) not null;
update persistent_logins p set username = (select a.product || ':' || a.username from user_account a
    where a.username = p.username);

-- El nombre de la restricción única del usuario lo generó H2, por eso se busca.
execute immediate 'alter table user_account drop constraint ' || (
    select tc.constraint_name from information_schema.table_constraints tc
    join information_schema.key_column_usage k on k.constraint_name = tc.constraint_name and k.table_name = tc.table_name
    where tc.table_name = 'USER_ACCOUNT' and tc.constraint_type = 'UNIQUE' and k.column_name = 'USERNAME');
alter table user_account add constraint user_account_username_product unique (username, product);
