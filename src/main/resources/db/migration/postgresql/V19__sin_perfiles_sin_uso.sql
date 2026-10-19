-- Perfiles que nunca se ofrecieron (federaciones FSP y CNT, sin datos relevados): se quitan del modelo.
delete from usuario_perfil where perfil in ('CLUBFSP', 'CLUBCNT', 'PLAYERFSP', 'PLAYERCNT');
