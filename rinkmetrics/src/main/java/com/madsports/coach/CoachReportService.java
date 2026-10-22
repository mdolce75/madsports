package com.madsports.coach;

import com.madsports.account.UserAccount;
import com.madsports.coach.SquadService.SquadPlayer;
import com.madsports.pdf.Templates;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Informes del DT: el de un partido (con sus eventos) y los de los partidos finalizados de un período (mes, bimestre,
 * semestre o campeonato), del equipo o de un jugador; en JSON y en PDF (plantilla {@code templates/coach-report.html}).
 */
@Service
public class CoachReportService {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /** Un PDF listo para descargar. */
    public record Pdf(String file, byte[] content) {
    }

    private final LiveMatchRepository matches;
    private final MatchActionRepository actions;
    private final SquadService squads;
    private final TeamService teams;
    private final Templates pdf;
    private final Clock clock;

    public CoachReportService(LiveMatchRepository matches, MatchActionRepository actions, SquadService squads,
                            TeamService teams, Templates pdf, Clock clock) {
        this.matches = matches;
        this.actions = actions;
        this.squads = squads;
        this.teams = teams;
        this.pdf = pdf;
        this.clock = clock;
    }

    static boolean finished(LiveMatch p) {
        return p.getStatus() == MatchStatus.FINISHED || p.getStatus() == MatchStatus.CLOSED;
    }

    /** Informe de un partido del DT (en cualquier estado: también sirve durante el partido). */
    public CoachReport match(UserAccount u, Long id, String player) {
        LiveMatch p = matches.findByIdAndUserId(id, u.getId())
                .orElseThrow(() -> new CoachService.NotFoundException("No existe ese partido."));
        String title = u.getClubName() + " " + p.getGoalsFor() + " - " + p.getGoalsAgainst() + " " + p.getOpponent();
        String detail = String.join(" · ", Stream.of(teams.team(u, p.getTeamId()).getName(),
                p.isHome() ? "Local" : "Visitante", p.getTournament(), p.getDate().format(DATE_FORMAT))
                .filter(s -> s != null && !s.isBlank()).toList());
        return build(u, title, detail, true, List.of(p), player);
    }

    /** Informe de los partidos finalizados del equipo en el período. */
    public CoachReport period(UserAccount u, Long teamId, ReportPeriod period, String value, String player) {
        if (period == null) {
            throw new IllegalArgumentException("Elegí el período del informe.");
        }
        Team e = teams.team(u, teamId);
        String v = value == null ? "" : value.trim();
        String title = period.name(v);
        List<LiveMatch> ps = matches.findByUserIdOrderByDateDescIdDesc(u.getId()).stream()
                .filter(p -> p.getTeamId().equals(e.getId()))
                .filter(CoachReportService::finished)
                .filter(p -> period.value(p.getDate(), p.getTournament()).equals(v))
                .toList().reversed();
        if (ps.isEmpty()) {
            throw new IllegalArgumentException("No hay partidos finalizados en " + title + ".");
        }
        String detail = e.getName() + " · " + ps.size() + (ps.size() == 1 ? " partido" : " partidos");
        return build(u, period == ReportPeriod.CHAMPIONSHIP && !v.isEmpty() ? "Campeonato " + title : title, detail,
                false, ps, player);
    }

    public Pdf pdf(CoachReport report) {
        String name = report.singleMatch()
                ? "informe-" + slug(report.matches().getFirst().opponent()) + "-" + report.matches().getFirst().date()
                : "informe-" + slug(report.title());
        if (report.player() != null) {
            name += "-" + slug(report.player().name());
        }
        return new Pdf(name + ".pdf", pdf.pdf("dt-informe", Map.of("r", report)));
    }

    public String html(CoachReport report) {
        return pdf.html("dt-informe", Map.of("r", report));
    }

    private CoachReport build(UserAccount u, String title, String detail, boolean singleMatch, List<LiveMatch> ps,
                            String player) {
        List<MatchAction> list = actions.findByMatchIdInOrderByMatchIdAscPeriodAscAtSecondAscCreatedAtAsc(
                ps.stream().map(LiveMatch::getId).toList()).stream().filter(MatchAction::isActive).toList();
        LiveMatch first = ps.getFirst();
        return CoachReport.build(title, detail, u.getClubName(), teams.team(u, first.getTeamId()).getName(),
                LocalDate.now(clock), singleMatch, ps, list, squad(u), player);
    }

    /** Jugadores del DT (sección Equipo) para el nombre, el número y los arqueros. */
    private Map<Integer, SquadPlayer> squad(UserAccount u) {
        return squads.players(u).stream()
                .collect(Collectors.toMap(SquadPlayer::id, Function.identity(), (a, b) -> a));
    }

    static String slug(String s) {
        String without = Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        String out = without.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return out.isEmpty() ? "dt" : out;
    }
}
