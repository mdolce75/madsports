-- El registro ya no pide nombre ni apellido: los jugadores los toman de la FPP; los clubes no los cargan.
alter table usuario alter column nombre drop not null;
alter table usuario alter column apellido drop not null;
