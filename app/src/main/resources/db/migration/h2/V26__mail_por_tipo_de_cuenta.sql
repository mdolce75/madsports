-- El email es único por tipo de cuenta (club, jugador o DT), ya no por producto: el mismo puede tener una cuenta de
-- club y otra de jugador en RinkReports (y una de DT en RinkMetrics). El tipo sale del perfil de la cuenta.
alter table user_account add column user_type varchar(10);
update user_account a set user_type = coalesce((select min(case when p.profile like 'PLAYER%' then 'PLAYER'
                                                                 when p.profile like 'COACH%' then 'COACH'
                                                                 else 'CLUB' end)
                                                from user_profile p where p.user_id = a.id), 'CLUB');
alter table user_account alter column user_type set not null;
alter table user_account drop constraint user_account_email_product;
alter table user_account add constraint user_account_email_type unique (email, user_type);
