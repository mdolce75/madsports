-- Categoría y rama del jugador (las del equipo que eligió al registrarse): definen en qué categorías puede jugar.
alter table usuario add column categoria varchar(20);
alter table usuario add column rama varchar(10);
