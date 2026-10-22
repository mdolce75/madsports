package com.madsports.coach;

import com.madsports.account.SubscriptionPlan;
import com.madsports.account.UserAccount;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Equipos del DT (el ABM de equipos de la sección Equipo): un nombre libre, hasta los que permite su plan (free 1,
 * mensual 3). Cada partido y cada jugador son de un equipo; un equipo con partidos no se puede borrar.
 */
@Service
public class TeamService {

    /**
     * Un equipo con su balance: partidos finalizados jugados, ganados, empatados y perdidos.
     *
     * @param categoriaSidgad y {@code sidgadBranch}: la categoría del club de la que salió el plantel (null: nombre libre)
     */
    public record TeamView(Long id, String name, int played, int won, int drawn, int lost,
                             String sidgadCategory, String sidgadBranch) {
    }

    private final TeamRepository teams;
    private final LiveMatchRepository matches;
    private final TeamPlayerRepository players;
    private final Clock clock;

    public TeamService(TeamRepository teams, LiveMatchRepository matches, TeamPlayerRepository players,
                           Clock clock) {
        this.teams = teams;
        this.matches = matches;
        this.players = players;
        this.clock = clock;
    }

    public List<TeamView> list(UserAccount u) {
        Map<Long, List<LiveMatch>> byTeam = matches.findByUserIdOrderByDateDescIdDesc(u.getId()).stream()
                .filter(CoachReportService::finished)
                .collect(Collectors.groupingBy(LiveMatch::getTeamId));
        return teams.findByUserIdOrderByIdAsc(u.getId()).stream().map(e -> {
            List<LiveMatch> ps = byTeam.getOrDefault(e.getId(), List.of());
            return new TeamView(e.getId(), e.getName(), ps.size(),
                    (int) ps.stream().filter(p -> p.getGoalsFor() > p.getGoalsAgainst()).count(),
                    (int) ps.stream().filter(p -> p.getGoalsFor() == p.getGoalsAgainst()).count(),
                    (int) ps.stream().filter(p -> p.getGoalsFor() < p.getGoalsAgainst()).count(),
                    e.getSidgadCategory(), e.getSidgadBranch());
        }).toList();
    }

    /** El equipo pedido, que tiene que ser del DT; sin id, el primero. */
    public Team team(UserAccount u, Long id) {
        if (id == null) {
            return teams.findByUserIdOrderByIdAsc(u.getId()).stream().findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Primero creá un equipo en la sección Equipo."));
        }
        return teams.findByIdAndUserId(id, u.getId())
                .orElseThrow(() -> new CoachService.NotFoundException("No existe ese equipo."));
    }

    @Transactional
    public TeamView create(UserAccount u, String name) {
        Team e = created(u, name, null, null);
        return new TeamView(e.getId(), e.getName(), 0, 0, 0, 0, null, null);
    }

    /**
     * Crea el equipo (con su nombre validado y dentro del cupo del plan). Con {@code category} y {@code branch} queda
     * anotado de qué categoría del club en SIDGAD sale su plantel.
     */
    @Transactional
    public Team created(UserAccount u, String name, String category, String branch) {
        String n = name(u, name, null);
        SubscriptionPlan plan = u.getSubscriptionPlan();
        int max = plan == null ? 0 : plan.getMaxTeams();
        if (max > 0 && teams.findByUserIdOrderByIdAsc(u.getId()).size() >= max) {
            throw new CoachService.CoachConflictException("Tu plan " + plan.getName() + " permite " + (max == 1 ? "1 equipo" : "hasta " + max
                    + " equipos") + ". Para sumar otro, borrá uno sin partidos"
                    + ("COACH_FREE".equals(plan.getCode()) ? " o pasate al plan mensual." : "."));
        }
        return teams.save(new Team(u.getId(), n, category, branch, clock.instant()));
    }

    @Transactional
    public TeamView rename(UserAccount u, Long id, String name) {
        Team e = team(u, id);
        e.rename(name(u, name, e));
        return list(u).stream().filter(v -> v.id().equals(id)).findFirst().orElseThrow();
    }

    /** Borra un equipo sin partidos; sus jugadores siguen en los otros equipos (o quedan sin equipo). */
    @Transactional
    public void delete(UserAccount u, Long id) {
        Team e = team(u, id);
        long n = matches.countByTeamId(e.getId());
        if (n > 0) {
            throw new CoachService.CoachConflictException(e.getName() + " tiene " + n + (n == 1 ? " partido cargado" : " partidos cargados")
                    + ": no se puede borrar.");
        }
        players.findByUserIdAndActiveTrue(u.getId()).forEach(j -> j.removeFrom(e.getId()));
        teams.delete(e);
    }

    private String name(UserAccount u, String name, Team current) {
        String n = name == null ? "" : name.trim().replaceAll("\\s+", " ");
        if (n.isEmpty()) {
            throw new IllegalArgumentException("Escribí el nombre del equipo.");
        }
        if (n.length() > 60) {
            throw new IllegalArgumentException("El nombre del equipo es muy largo (hasta 60 letras).");
        }
        boolean same = current != null && current.getName().equalsIgnoreCase(n);
        if (!same && teams.existsByUserIdAndNameIgnoreCase(u.getId(), n)) {
            throw new CoachService.CoachConflictException("Ya tenés un equipo llamado " + n + ".");
        }
        return n;
    }
}
