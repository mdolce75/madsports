-- DT free: hasta 3 equipos (antes 1); sigue con 5 partidos por mes.
update subscription_plan set max_teams = 3 where code = 'COACH_FREE';
