-- Cada cuenta es de un producto de Mad Sports: RinkReports (perfiles Club y Jugador) o RinkMetrics (perfil DT). Cada
-- uno tiene su registro y su ingreso, y el mismo email puede tener una cuenta en cada producto: el email deja de ser
-- único en toda la tabla y pasa a serlo dentro del producto. El usuario sigue siendo único en toda la web.
alter table usuario add column producto varchar(10) default 'REPORTS' not null;
update usuario set producto = 'METRICS'
    where id in (select usuario_id from usuario_perfil where perfil in ('DTFPP', 'DTAMP'));

-- El nombre de la restricción única del email lo generó H2, por eso se busca.
execute immediate 'alter table usuario drop constraint ' || (
    select tc.constraint_name from information_schema.table_constraints tc
    join information_schema.key_column_usage k on k.constraint_name = tc.constraint_name and k.table_name = tc.table_name
    where tc.table_name = 'USUARIO' and tc.constraint_type = 'UNIQUE' and k.column_name = 'EMAIL');
alter table usuario add constraint usuario_email_producto unique (email, producto);
