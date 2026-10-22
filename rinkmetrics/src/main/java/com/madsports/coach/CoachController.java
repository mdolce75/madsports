package com.madsports.coach;

import com.madsports.account.UserAccount;
import com.madsports.account.UserAccountRepository;
import com.madsports.coach.CoachService.NewAction;
import com.madsports.coach.CoachService.ClockAction;
import com.madsports.coach.CoachService.NewMatch;
import com.madsports.coach.CoachService.MatchView;
import com.madsports.coach.TeamService.TeamView;
import com.madsports.coach.SquadService.ClubCategoryView;
import com.madsports.coach.SquadService.PlayerData;
import com.madsports.coach.SquadService.SquadPlayer;
import com.madsports.coach.SquadService.SidgadPlayer;
import com.madsports.coach.SquadService.NewTeam;
import com.madsports.sidgad.SidgadClient.SidgadException;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;

/**
 * RinkMetrics en vivo (perfiles DTFPP y DTAMP): equipos y jugadores (ABM), partidos, acciones, reloj e informes. Cada
 * DT ve solo sus equipos, sus jugadores y sus partidos; los de otro DT responden 404.
 */
@RestController
@RequestMapping("/api/dt")
public class CoachController {

    /** @param segundos solo para AJUSTAR: segundos de tiempo jugado a sumar (o restar, si es negativo) */
    public record ClockRequest(ClockAction action, Integer seconds) {
    }

    public record TeamRequest(String name) {
    }

    private final CoachService coach;
    private final CoachReportService reports;
    private final SquadService squads;
    private final TeamService teams;
    private final UserAccountRepository users;

    public CoachController(CoachService coach, CoachReportService reports, SquadService squads, TeamService teams,
                        UserAccountRepository users) {
        this.coach = coach;
        this.reports = reports;
        this.squads = squads;
        this.teams = teams;
        this.users = users;
    }

    /** Equipos del DT, con su balance de partidos finalizados (jugados, ganados, empatados y perdidos). */
    @GetMapping("/equipos")
    public List<TeamView> teams(Authentication auth) {
        return teams.list(currentUser(auth));
    }

    /**
     * Equipo nuevo: con nombre libre, o desde una categoría del club ({@code category} y {@code branch}) con su plantel
     * de SIDGAD, menos los {@code excluded}, que quedan como "no incluidos".
     */
    @PostMapping("/equipos")
    public ResponseEntity<TeamView> createTeam(@RequestBody NewTeam req, Authentication auth) {
        return ResponseEntity.status(HttpStatus.CREATED).body(squads.createTeam(currentUser(auth), req));
    }

    /** Las categorías del club del DT en SIDGAD, para armar un equipo con su plantel. */
    @GetMapping("/club/categorias")
    public List<ClubCategoryView> clubCategories(Authentication auth) {
        return squads.clubCategories(currentUser(auth));
    }

    /** El plantel de SIDGAD de una categoría del club (los jugadores de las actas del equipo). */
    @GetMapping("/club/plantel")
    public List<SidgadPlayer> sidgadSquad(@RequestParam String category, @RequestParam String branch,
                                             Authentication auth) {
        return squads.sidgadSquad(currentUser(auth), category, branch);
    }

    @PutMapping("/equipos/{id}")
    public TeamView renameTeam(@PathVariable Long id, @RequestBody TeamRequest req, Authentication auth) {
        return teams.rename(currentUser(auth), id, req.name());
    }

    /** Solo un equipo sin partidos (409 si tiene). */
    @DeleteMapping("/equipos/{id}")
    public ResponseEntity<Void> deleteTeam(@PathVariable Long id, Authentication auth) {
        teams.delete(currentUser(auth), id);
        return ResponseEntity.noContent().build();
    }

    /** Todos los jugadores del DT, con los equipos en que juega cada uno. */
    @GetMapping("/jugadores")
    public List<SquadPlayer> players(Authentication auth) {
        return squads.players(currentUser(auth));
    }

    @PostMapping("/jugadores")
    public ResponseEntity<SquadPlayer> createPlayer(@RequestBody PlayerData req, Authentication auth) {
        return ResponseEntity.status(HttpStatus.CREATED).body(squads.addPlayer(currentUser(auth), req));
    }

    @PutMapping("/jugadores/{jugadorId}")
    public SquadPlayer updatePlayer(@PathVariable int playerId, @RequestBody PlayerData req,
                                           Authentication auth) {
        return squads.modify(currentUser(auth), playerId, req);
    }

    /** Suma al equipo a un jugador que figuraba como "no incluido" (409 si su número ya es de otro del equipo). */
    @PostMapping("/jugadores/{jugadorId}/equipos/{equipoId}")
    public SquadPlayer includePlayer(@PathVariable int playerId, @PathVariable Long teamId, Authentication auth) {
        return squads.include(currentUser(auth), playerId, teamId);
    }

    @DeleteMapping("/jugadores/{jugadorId}")
    public ResponseEntity<Void> deactivatePlayer(@PathVariable int playerId, Authentication auth) {
        squads.deactivate(currentUser(auth), playerId);
        return ResponseEntity.noContent().build();
    }

    /** Plantel de un equipo (sin equipo, el primero): sus jugadores activos, por número. */
    @GetMapping("/plantel")
    public List<SquadPlayer> squad(@RequestParam(required = false) Long team, Authentication auth) {
        return squads.squad(currentUser(auth), team);
    }

    /** Acciones de todos los partidos del equipo, para las estadísticas y las fichas de los jugadores. */
    @GetMapping("/acciones")
    public List<CoachService.TeamAction> actions(@RequestParam(required = false) Long team, Authentication auth) {
        return coach.teamActions(currentUser(auth), team);
    }

    @PostMapping("/partidos")
    public ResponseEntity<MatchView> create(@RequestBody NewMatch req, Authentication auth) {
        return ResponseEntity.status(HttpStatus.CREATED).body(coach.create(currentUser(auth), req));
    }

    @GetMapping("/partidos")
    public List<MatchView> list(Authentication auth) {
        return coach.list(currentUser(auth));
    }

    @GetMapping("/partidos/{id}")
    public MatchView match(@PathVariable Long id, Authentication auth) {
        return coach.match(currentUser(auth), id);
    }

    @PostMapping("/partidos/{id}/acciones")
    public MatchView actions(@PathVariable Long id, @RequestBody List<NewAction> actions, Authentication auth) {
        return coach.enqueue(currentUser(auth), id, actions);
    }

    @DeleteMapping("/partidos/{id}/acciones/{accionId}")
    public MatchView delete(@PathVariable Long id, @PathVariable String actionId, Authentication auth) {
        return coach.delete(currentUser(auth), id, actionId);
    }

    @PostMapping("/partidos/{id}/reloj")
    public MatchView clock(@PathVariable Long id, @RequestBody ClockRequest req, Authentication auth) {
        return coach.clock(currentUser(auth), id, req.action(), req.seconds());
    }

    @PostMapping("/partidos/{id}/cerrar")
    public MatchView close(@PathVariable Long id, Authentication auth) {
        return coach.close(currentUser(auth), id);
    }

    /** Elimina el partido y todas sus acciones (también de las estadísticas y los informes). */
    @DeleteMapping("/partidos/{id}")
    public ResponseEntity<Void> remove(@PathVariable Long id, Authentication auth) {
        coach.remove(currentUser(auth), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/partidos/{id}/reabrir")
    public MatchView reopen(@PathVariable Long id, Authentication auth) {
        return coach.reopen(currentUser(auth), id);
    }

    /** Informe del partido: del equipo o, con {@code player} ("j" + id, o "m" + nombre), de ese jugador. */
    @GetMapping("/partidos/{id}/informe")
    public CoachReport report(@PathVariable Long id, @RequestParam(required = false) String player,
                             Authentication auth) {
        return reports.match(currentUser(auth), id, player);
    }

    @GetMapping("/partidos/{id}/informe.pdf")
    public ResponseEntity<byte[]> reportPdf(@PathVariable Long id, @RequestParam(required = false) String player,
                                             Authentication auth) {
        return pdf(reports.pdf(reports.match(currentUser(auth), id, player)));
    }

    /** Informe de los partidos finalizados del equipo en un mes, bimestre, semestre o campeonato. */
    @GetMapping("/informes")
    public CoachReport periodReport(@RequestParam(required = false) Long team, @RequestParam ReportPeriod period,
                                    @RequestParam(required = false) String value,
                                    @RequestParam(required = false) String player, Authentication auth) {
        return reports.period(currentUser(auth), team, period, value, player);
    }

    @GetMapping("/informes.pdf")
    public ResponseEntity<byte[]> periodReportPdf(@RequestParam(required = false) Long team,
                                                    @RequestParam ReportPeriod period,
                                                    @RequestParam(required = false) String value,
                                                    @RequestParam(required = false) String player,
                                                    Authentication auth) {
        return pdf(reports.pdf(reports.period(currentUser(auth), team, period, value, player)));
    }

    /** El PDF se abre en el navegador (inline); desde ahí se guarda o se comparte. */
    private static ResponseEntity<byte[]> pdf(CoachReportService.Pdf p) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(p.file()).build()
                        .toString())
                .body(p.content());
    }

    private UserAccount currentUser(Authentication auth) {
        return users.findByUsername(auth.getName()).filter(UserAccount::isCoach)
                .orElseThrow(() -> new AccessDeniedException("Tu usuario no es DT."));
    }

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
    ProblemDetail badRequest(Exception e) {
        String detail = e instanceof IllegalArgumentException ? e.getMessage() : "El pedido no es válido.";
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
    }

    @ExceptionHandler(CoachService.NotFoundException.class)
    ProblemDetail notFound(CoachService.NotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler({CoachService.CoachConflictException.class, IllegalStateException.class})
    ProblemDetail conflict(RuntimeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(SidgadException.class)
    ProblemDetail sidgad(SidgadException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, "No se pudo leer SIDGAD: " + e.getMessage());
    }
}
