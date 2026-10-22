-- Cada cuenta es de un producto de Mad Sports: RinkReports (perfiles Club y Jugador) o RinkMetrics (perfil DT). Cada
-- uno tiene su registro y su ingreso, y el mismo email puede tener una cuenta en cada producto: el email deja de ser
-- único en toda la tabla y pasa a serlo dentro del producto. El usuario sigue siendo único en toda la web.
alter table usuario add column producto varchar(10) default 'REPORTS' not null;
update usuario set producto = 'METRICS'
    where id in (select usuario_id from usuario_perfil where perfil in ('DTFPP', 'DTAMP'));

-- En PostgreSQL la restricción tiene el nombre que le da el motor por defecto.
alter table usuario drop constraint usuario_email_key;
alter table usuario add constraint usuario_email_producto unique (email, producto);
