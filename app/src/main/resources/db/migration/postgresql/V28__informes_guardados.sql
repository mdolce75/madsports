-- Informes guardados para tenerlos a mano desde el historial (ReportArchive): sus archivos van a una carpeta; acá,
-- si siguen guardados, el nombre del PDF y su tamaño.
alter table requested_report add column archived boolean default false not null;
alter table requested_report add column file_name varchar(200);
alter table requested_report add column size_bytes bigint;
