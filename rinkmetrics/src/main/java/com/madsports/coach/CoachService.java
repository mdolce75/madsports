package com.madsports.coach;

import com.madsports.account.SubscriptionPlan;
import com.madsports.account.UserAccount;
import com.madsports.coach.SquadService.SquadPlayer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * RinkMetrics en vivo: partidos que carga el DT de sus equipos, sus acciones (cada una de un jugador, con tiempo y
 * segundo) y el reloj. Todo se valida contra el DT: sus equipos, su cupo del mes y sus partidos.
 */
@Service
public class CoachService {

    /** El partido no existe o no es del DT (para otro DT, es como si no existiera). */
    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String message) {
            super(message);
        }
    }

    /** Choca con algo ya cargado (el cupo de partidos del mes). */
    public static class CoachConflictException extends RuntimeException {
        public CoachConflictException(String message) {
            super(message);
        }
    }

    /** Duración de cada tiempo si la competición no la informa. */
    static final int DEFAULT_MINUTES = 25;
    private static final Pattern UUID = Pattern.compile("[0-9a-fA-F-]{36}");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM");

    /**
     * Un partido nuevo: equipo del DT (sin equipo, el primero), rival (escrito a mano), fecha (sin fecha, hoy), si el
     * equipo es local, el campeonato (opcional: agrupa los partidos en los informes) y los minutos de cada tiempo (sin
     * minutos, 25).
     */
    public record NewMatch(Long team, String opponent, LocalDate date, Boolean home, String tournament,
                               Integer minutes) {
    }

    /**
     * Una acción que manda el teléfono.
     *
     * @param id            uuid generado en el teléfono
     * @param jugadorId     id_player de SIDGAD, o 0 para un jugador agregado a mano (con {@code playerName})
     * @param segundo       segundos jugados del tiempo (25:00 menos lo que marca el reloj)
     */
    public record NewAction(String id, int playerId, String playerName, ActionType kind, Outcome outcome,
                              int period, int atSecond) {
    }

    /** @param restante lo que marca el reloj, en segundos (cuenta hacia atrás) */
    public record ClockView(int period, int elapsed, int remaining, boolean running) {
    }

    public record ActionView(String id, int playerId, String playerName, ActionType kind, Outcome outcome,
                             int period, int atSecond) {

        static ActionView of(MatchAction a) {
            return new ActionView(a.getId(), a.getPlayerId(), a.getPlayerName(), a.getKind(), a.getOutcome(),
                    a.getPeriod(), a.getAtSecond());
        }
    }

    /** Una acción de un partido del equipo (para las fichas de los jugadores). */
    public record TeamAction(Long matchId, String id, int playerId, String playerName, ActionType kind,
                                 Outcome outcome, int period, int atSecond) {
    }

    /** @param acciones las activas, en orden de juego (vacía en la lista de partidos) */
    /** @param equipo nombre del equipo del DT */
    public record MatchView(Long id, Long teamId, String team, Integer idp, String tournament, String opponent,
                              LocalDate date, boolean home, int periodMinutes, MatchStatus status, int goalsFor,
                              int goalsAgainst, ClockView clock, List<ActionView> actions) {
    }

    private final LiveMatchRepository matches;
    private final MatchActionRepository actions;
    private final SquadService squads;
    private final TeamService teams;
    private final StartedMatchRepository started;
    private final Clock clock;

    public CoachService(LiveMatchRepository matches, MatchActionRepository actions, SquadService squads,
                     TeamService teams, StartedMatchRepository started, Clock clock) {
        this.matches = matches;
        this.actions = actions;
        this.squads = squads;
        this.teams = teams;
        this.started = started;
        this.clock = clock;
    }

    /**
     * Empieza a cargar un partido. Si el DT ya tiene uno de ese equipo con el mismo rival y la misma fecha, lo devuelve
     * para retomarlo (no cuenta de nuevo para el cupo). Cada partido nuevo cuenta para el cupo del mes.
     */
    @Transactional
    public MatchView create(UserAccount u, NewMatch n) {
        Team e = teams.team(u, n.team());
        String opponent = n.opponent() == null ? "" : n.opponent().trim().replaceAll("\\s+", " ");
        if (opponent.isEmpty()) {
            throw new IllegalArgumentException("Escribí el rival.");
        }
        if (opponent.length() > 60) {
            throw new IllegalArgumentException("El nombre del rival es muy largo.");
        }
        String tournament = n.tournament() == null ? "" : n.tournament().trim().replaceAll("\\s+", " ");
        if (tournament.length() > 60) {
            throw new IllegalArgumentException("El nombre del campeonato es muy largo.");
        }
        int minutes = n.minutes() == null ? DEFAULT_MINUTES : n.minutes();
        if (minutes < 10 || minutes > 40) {
            throw new IllegalArgumentException("Cada tiempo dura entre 10 y 40 minutos.");
        }
        LocalDate date = n.date() == null ? LocalDate.now(clock) : n.date();
        var existing = matches.findByUserIdAndTeamIdAndOpponentAndDate(u.getId(), e.getId(), opponent, date);
        if (existing.isPresent()) {
            return view(existing.get(), true);
        }
        checkQuota(u);
        LiveMatch p = new LiveMatch(u.getId(), u.federationCode(), u.getClubId(), e.getId(), null, null,
                tournament.isEmpty() ? null : tournament, opponent, date, n.home() == null || n.home(), minutes, clock.instant());
        started.save(new StartedMatch(u.getId(), p.getCreatedAt()));
        return view(matches.save(p), true, e.getName());
    }

    /** Partidos cargados por el DT, los más recientes primero (sin las acciones). */
    public List<MatchView> list(UserAccount u) {
        return matches.findByUserIdOrderByDateDescIdDesc(u.getId()).stream().map(p -> view(p, false)).toList();
    }

    /**
     * Las acciones activas de todos los partidos que el DT cargó de un equipo: con eso la pantalla arma la ficha de
     * cada jugador (pentágono e índice).
     */
    public List<TeamAction> teamActions(UserAccount u, Long teamId) {
        Team e = teams.team(u, teamId);
        List<Long> ids = matches.findByUserIdOrderByDateDescIdDesc(u.getId()).stream()
                .filter(p -> p.getTeamId().equals(e.getId()))
                .map(LiveMatch::getId).toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        return actions.findByMatchIdInOrderByMatchIdAscPeriodAscAtSecondAscCreatedAtAsc(ids).stream()
                .filter(MatchAction::isActive)
                .map(a -> new TeamAction(a.getMatchId(), a.getId(), a.getPlayerId(), a.getPlayerName(),
                        a.getKind(), a.getOutcome(), a.getPeriod(), a.getAtSecond()))
                .toList();
    }

    public MatchView match(UserAccount u, Long id) {
        return view(own(u, id), true);
    }

    /**
     * Agrega acciones (una o una tanda de las cargadas sin señal). Una acción con un id ya guardado se ignora: el
     * teléfono puede reenviar sin duplicar.
     */
    @Transactional
    public MatchView enqueue(UserAccount u, Long id, List<NewAction> newOnes) {
        LiveMatch p = own(u, id);
        if (!p.acceptsActions()) {
            throw new IllegalStateException("El partido está cerrado: reabrilo para cargar acciones.");
        }
        Map<Integer, SquadPlayer> squad = null;
        Instant now = clock.instant();
        for (NewAction a : newOnes == null ? List.<NewAction>of() : newOnes) {
            validateAction(a, p);
            if (actions.existsById(a.id())) {
                continue;
            }
            String name;
            if (a.playerId() > 0) {
                if (squad == null) {
                    squad = squads.squad(u, p.getTeamId()).stream()
                            .collect(Collectors.toMap(SquadPlayer::id, Function.identity()));
                }
                SquadPlayer j = squad.get(a.playerId());
                if (j == null) {
                    throw new IllegalArgumentException("El jugador " + a.playerId() + " no está en el plantel del equipo.");
                }
                name = j.name();
            } else {
                if (a.playerName() == null || a.playerName().isBlank()) {
                    throw new IllegalArgumentException("Elegí el jugador de la acción.");
                }
                name = a.playerName().trim();
            }
            actions.save(new MatchAction(a.id(), p.getId(), Math.max(0, a.playerId()), name, a.kind(), a.outcome(),
                    a.period(), a.atSecond(), now));
        }
        recalculate(p);
        return view(p, true);
    }

    /** Deshacer: la acción queda borrada y el marcador se recalcula. */
    @Transactional
    public MatchView delete(UserAccount u, Long id, String actionId) {
        LiveMatch p = own(u, id);
        if (!p.acceptsActions()) {
            throw new IllegalStateException("El partido está cerrado: reabrilo para borrar acciones.");
        }
        MatchAction a = actions.findById(actionId).filter(x -> x.getMatchId().equals(p.getId()))
                .orElseThrow(() -> new NotFoundException("No existe esa acción en el partido."));
        a.delete(clock.instant());
        recalculate(p);
        return view(p, true);
    }

    public enum ClockAction { START, PAUSE, RESUME, END_PERIOD, ADJUST }

    /** Iniciar (1T o 2T), pausar, reanudar, terminar el tiempo o ajustar el reloj ({@code seconds} de tiempo jugado). */
    @Transactional
    public MatchView clock(UserAccount u, Long id, ClockAction action, Integer seconds) {
        LiveMatch p = own(u, id);
        Instant now = clock.instant();
        if (action == null) {
            throw new IllegalArgumentException("Indicá la acción del reloj.");
        }
        switch (action) {
            case START -> p.start(now);
            case PAUSE -> p.pause(now);
            case RESUME -> p.resume(now);
            case END_PERIOD -> p.endPeriod(now);
            case ADJUST -> p.adjust(seconds == null ? 0 : seconds, now);
        }
        return view(p, true);
    }

    @Transactional
    public MatchView close(UserAccount u, Long id) {
        LiveMatch p = own(u, id);
        p.close(clock.instant());
        return view(p, true);
    }

    /**
     * Elimina el partido y todas sus acciones (la base las borra en cascada): deja de contar en las estadísticas, las
     * fichas y los informes, pero sigue contando para el cupo del mes. No se puede deshacer.
     */
    @Transactional
    public void remove(UserAccount u, Long id) {
        matches.delete(own(u, id));
    }

    @Transactional
    public MatchView reopen(UserAccount u, Long id) {
        LiveMatch p = own(u, id);
        p.reopen(clock.instant());
        return view(p, true);
    }

    /**
     * El DT no puede empezar más partidos en el mes que los de su plan (0 = sin límite), ni después del último día de
     * un plan con fecha fija (el free, hasta el 31/12/2026). El mes cuenta desde el día de su suscripción (suscripto
     * el 15: del 15 al 14 del mes siguiente).
     */
    void checkQuota(UserAccount u) {
        SubscriptionPlan plan = u.getSubscriptionPlan();
        if (plan == null || !"COACH".equals(plan.getUserType())) {
            throw new IllegalStateException("Tu plan no incluye RinkMetrics.");
        }
        LocalDate today = LocalDate.now(clock);
        if (!plan.isValid(today)) {
            throw new CoachConflictException("Tu plan " + plan.getName() + " terminó el "
                    + plan.getValidUntil().format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
                    + ". Pasate al plan mensual para seguir cargando partidos.");
        }
        if (plan.getMatchesPerMonth() <= 0) {
            return;
        }
        LocalDate from = monthStart(u.getSubscriptionFrom() == null ? today : u.getSubscriptionFrom(), today);
        long used = started.countByUserIdAndCreatedAtGreaterThanEqual(u.getId(),
                from.atStartOfDay(clock.getZone()).toInstant());
        if (used >= plan.getMatchesPerMonth()) {
            throw new CoachConflictException("Ya cargaste los " + plan.getMatchesPerMonth() + " partidos de este mes. El cupo se "
                    + "renueva el " + from.plusMonths(1).format(DAY) + ".");
        }
    }

    /** Primer día del mes de suscripción que contiene a {@code today}. */
    static LocalDate monthStart(LocalDate subscription, LocalDate today) {
        long months = Math.max(0, ChronoUnit.MONTHS.between(subscription, today));
        LocalDate from = subscription.plusMonths(months);
        return from.isAfter(today) ? subscription.plusMonths(months - 1) : from;
    }

    private void validateAction(NewAction a, LiveMatch p) {
        if (a == null || a.id() == null || !UUID.matcher(a.id()).matches()) {
            throw new IllegalArgumentException("Cada acción lleva su id (uuid).");
        }
        if (a.kind() == null) {
            throw new IllegalArgumentException("Falta el tipo de la acción.");
        }
        if (!a.kind().accepts(a.outcome())) {
            throw new IllegalArgumentException("El resultado " + a.outcome() + " no corresponde a " + a.kind() + ".");
        }
        if (a.period() < 1 || a.period() > 2) {
            throw new IllegalArgumentException("El tiempo es 1 o 2.");
        }
        if (a.atSecond() < 0 || a.atSecond() > p.duration()) {
            throw new IllegalArgumentException("El minuto está fuera del tiempo.");
        }
    }

    /** Marcador a partir de las acciones activas. */
    private void recalculate(LiveMatch p) {
        List<MatchAction> activeActions = actions.findByMatchIdOrderByPeriodAscAtSecondAscCreatedAtAsc(p.getId()).stream()
                .filter(MatchAction::isActive).toList();
        p.score((int) activeActions.stream().filter(a -> a.getKind().isGoalFor(a.getOutcome())).count(),
                (int) activeActions.stream().filter(a -> a.getKind().isGoalAgainst(a.getOutcome())).count());
    }

    private LiveMatch own(UserAccount u, Long id) {
        return matches.findByIdAndUserId(id, u.getId())
                .orElseThrow(() -> new NotFoundException("No existe ese partido."));
    }

    /** Vista de un partido leído con su equipo. */
    private MatchView view(LiveMatch p, boolean withActions) {
        return view(p, withActions, p.getTeam() == null ? "" : p.getTeam().getName());
    }

    private MatchView view(LiveMatch p, boolean withActions, String team) {
        Instant now = clock.instant();
        List<ActionView> list = withActions && p.getId() != null
                ? actions.findByMatchIdOrderByPeriodAscAtSecondAscCreatedAtAsc(p.getId()).stream()
                        .filter(MatchAction::isActive).map(ActionView::of).toList()
                : List.of();
        return new MatchView(p.getId(), p.getTeamId(), team, p.getIdp(),
                p.getTournament(), p.getOpponent(),
                p.getDate(), p.isHome(), p.getPeriodMinutes(), p.getStatus(), p.getGoalsFor(), p.getGoalsAgainst(),
                new ClockView(p.getCurrentPeriod(), p.elapsed(now), p.remaining(now), p.running()), list);
    }
}
