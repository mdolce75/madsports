-- Usuario de RinkReports (perfil REPORTS): un solo plan, Free, sin costo y sin límite de informes hasta el 31/12/2026
-- (reports_per_month = 0). Los precios se definen más adelante para todos los usuarios.
insert into subscription_plan (code, name, user_type, price_usd, reports_per_month, max_teams, matches_per_month,
                               valid_until)
    values ('FREE', 'Free', 'REPORTS', 0, 0, 0, 0, date '2026-12-31');
