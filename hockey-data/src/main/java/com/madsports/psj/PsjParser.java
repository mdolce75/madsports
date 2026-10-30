package com.madsports.psj;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.madsports.sidgad.model.Models.EventType;
import com.madsports.sidgad.model.Models.LineupPlayer;
import com.madsports.sidgad.model.Models.Match;
import com.madsports.sidgad.model.Models.MatchEvent;
import com.madsports.sidgad.model.Models.PlayerStats;
import com.madsports.sidgad.model.Models.StandingRow;
import com.madsports.sidgad.model.Models.TeamRef;

import java.text.Normalizer;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pasa los JSON de la API de gestiondepsj a los modelos de los informes ({@code Models}). La API identifica a los
 * equipos por club (no hay un id de equipo por torneo como en SIDGAD): el id de equipo es el del club.
 * <p>
 * Los eventos de un partido traen el nombre del jugador, no su id: se cruzan con la planilla del partido por club y
 * nombre normalizado (sin acentos ni espacios de más). Los que no cruzan quedan sin jugador y se informan en
 * {@link Sheet#unmatched()}.
 */
public final class PsjParser {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ZoneId ARGENTINA = ZoneId.of("America/Argentina/Buenos_Aires");
    private static final Pattern YEAR = Pattern.compile("\\b(20\\d{2})\\b");

    private PsjParser() {
    }

    /** Un torneo de la federación ("A1 PRIMERA TORNEO APERTURA 2026", id 200004). */
    public record Tournament(int id, String name) {
    }

    /** Una fecha (fase) del torneo, en su orden. */
    public record Phase(int id, int order) {
    }

    /**
     * Un partido leído: sus eventos y las planillas de los dos equipos, con los ids de club del local y del visitante.
     *
     * @param unmatched eventos con jugador que no figura en la planilla de su equipo ("Club · Nombre")
     */
    public record Sheet(int homeClubId, int awayClubId, List<MatchEvent> events, List<LineupPlayer> lineups,
                        List<String> unmatched) {

        static final Sheet EMPTY = new Sheet(0, 0, List.of(), List.of(), List.of());
    }

    public static List<Tournament> tournaments(String json) {
        List<Tournament> out = new ArrayList<>();
        for (JsonNode t : array(json)) {
            out.add(new Tournament(t.path("id").asInt(), text(t, "name")));
        }
        return out;
    }

    /** El año del nombre del torneo ("… APERTURA 2026"); vacío si no lo dice. */
    public static OptionalInt year(String name) {
        Matcher m = YEAR.matcher(name);
        return m.find() ? OptionalInt.of(Integer.parseInt(m.group(1))) : OptionalInt.empty();
    }

    /** Los clubes del torneo según su tabla, con su escudo ({@code logo_url}). */
    public static List<TeamRef> teams(String standingsJson) {
        List<TeamRef> out = new ArrayList<>();
        for (JsonNode s : array(standingsJson)) {
            int id = s.path("id").asInt();
            String name = text(s, "club_name");
            out.add(new TeamRef(id, id, code(name), name, text(s, "logo_url")));
        }
        return out;
    }

    /** Tabla de posiciones; con zonas (Copa), la posición se cuenta dentro de cada zona. */
    public static List<StandingRow> standings(String json) {
        List<StandingRow> out = new ArrayList<>();
        Map<String, Integer> positions = new HashMap<>();
        for (JsonNode s : array(json)) {
            String group = text(s, "zone");
            String name = text(s, "club_name");
            out.add(new StandingRow(group, positions.merge(group, 1, Integer::sum), s.path("id").asInt(), name,
                    code(name), s.path("points").asInt(), s.path("matches_played").asInt(), s.path("wins").asInt(),
                    s.path("draws").asInt(), s.path("losses").asInt(), s.path("goals_for").asInt(),
                    s.path("goals_against").asInt(), s.path("goal_difference").asInt()));
        }
        return out;
    }

    public static List<Phase> phases(String json) {
        List<Phase> out = new ArrayList<>();
        for (JsonNode p : array(json)) {
            out.add(new Phase(p.path("id").asInt(), p.path("order").asInt()));
        }
        return out;
    }

    /**
     * Los partidos de una fecha. Traen los clubes por nombre: el id sale de los equipos del torneo ({@code teams}; 0
     * si no figura). Fecha UTC → día en Argentina. Solo los terminados ({@code completed}) tienen resultado; el de un
     * W.O. ({@code wo_home}/{@code wo_away}) es el oficial que publica la federación, y la definición por penales
     * ({@code penalties_*}) no cambia el resultado del partido.
     */
    public static List<Match> matches(String json, int round, List<TeamRef> teams) {
        Map<String, Integer> ids = new HashMap<>();
        teams.forEach(t -> ids.put(norm(t.name()), t.clubId()));
        List<Match> out = new ArrayList<>();
        for (JsonNode m : array(json)) {
            boolean done = "completed".equals(text(m, "status"));
            String home = text(m, "home_club_name");
            String away = text(m, "away_club_name");
            int homeId = ids.getOrDefault(norm(home), 0);
            int awayId = ids.getOrDefault(norm(away), 0);
            out.add(new Match(m.path("id").asInt(), round, date(text(m, "match_date")), time(text(m, "match_time")),
                    text(m, "venue"), homeId, homeId, home, awayId, awayId, away,
                    done ? m.path("score_home").asInt() : null, done ? m.path("score_away").asInt() : null));
        }
        return out;
    }

    /** El detalle de un partido con sus eventos agregados en {@code events} (lo que guarda {@link PsjClient#match}). */
    static String withEvents(String detail, String events) {
        try {
            ObjectNode node = (ObjectNode) JSON.readTree(detail);
            node.set("events", JSON.readTree(events));
            return JSON.writeValueAsString(node);
        } catch (JsonProcessingException | ClassCastException e) {
            throw new PsjClient.PsjException("Respuesta inválida de gestiondepsj: " + e.getMessage());
        }
    }

    /** ¿El partido ya terminó? (entonces no cambia y se guarda). */
    static boolean completed(String body) {
        return "completed".equals(text(tree(body).path("match"), "status"));
    }

    /**
     * Un partido de {@link PsjClient#match}: las planillas (solo jugadores, sin cuerpo técnico) y los eventos que se
     * analizan. Tiempos: {@code primer_tiempo} = 1, {@code segundo_tiempo} = 2 (otro, 0). Tipos: gol, tarjetas
     * amarilla, azul y roja, y faltas ({@code foul}, {@code direct_foul} y {@code advantage_foul} son falta de equipo).
     * Las planillas suman los goles y tarjetas de cada jugador en el partido.
     */
    public static Sheet sheet(String body) {
        if (body == null || body.isBlank()) {
            return Sheet.EMPTY;
        }
        JsonNode root = tree(body);
        JsonNode match = root.path("match");
        int homeId = match.path("home_club_id").asInt();
        int awayId = match.path("away_club_id").asInt();
        String homeName = text(match, "home_club_name");
        String awayName = text(match, "away_club_name");

        Map<String, JsonNode> roster = new LinkedHashMap<>();   // "club|NOMBRE NORMALIZADO" -> jugador
        Map<Integer, Boolean> sideOf = new LinkedHashMap<>();
        for (boolean home : new boolean[]{true, false}) {
            int club = home ? homeId : awayId;
            for (JsonNode p : root.path(home ? "home_roster" : "away_roster")) {
                if (!"player".equals(text(p, "type"))) {
                    continue;
                }
                roster.putIfAbsent(club + "|" + norm(text(p, "first_name") + " " + text(p, "last_name")), p);
                sideOf.putIfAbsent(p.path("id").asInt(), home);
            }
        }

        List<MatchEvent> events = new ArrayList<>();
        List<String> unmatched = new ArrayList<>();
        Map<Integer, int[]> counts = new HashMap<>();   // jugador -> {goles, amarillas, azules, rojas}
        for (JsonNode e : root.path("events")) {
            EventType type = eventType(text(e, "event_type"));
            if (type == null) {
                continue;
            }
            String clubName = norm(text(e, "club_name"));
            int club = clubName.equals(norm(homeName)) ? homeId : clubName.equals(norm(awayName)) ? awayId : 0;
            String first = text(e, "player_first_name");
            String last = text(e, "player_last_name");
            String player = last.isEmpty() ? "" : title(first + " " + last);
            int playerId = 0;
            if (!last.isEmpty()) {
                JsonNode p = roster.get(club + "|" + norm(first + " " + last));
                if (p == null) {
                    unmatched.add(text(e, "club_name") + " · " + player);
                } else {
                    playerId = p.path("id").asInt();
                    int[] c = counts.computeIfAbsent(playerId, k -> new int[4]);
                    switch (type) {
                        case GOAL -> c[0]++;
                        case YELLOW_CARD -> c[1]++;
                        case BLUE_CARD -> c[2]++;
                        case RED_CARD -> c[3]++;
                        default -> { }
                    }
                }
            }
            events.add(new MatchEvent(period(text(e, "current_period")), e.path("minute").asInt(),
                    e.path("second").asInt(), type, club, club, club == homeId ? code(homeName) : club == awayId
                    ? code(awayName) : "", player, playerId));
        }

        List<LineupPlayer> lineups = new ArrayList<>();
        for (JsonNode p : roster.values()) {
            int id = p.path("id").asInt();
            boolean home = sideOf.get(id);
            int[] c = counts.getOrDefault(id, new int[4]);
            lineups.add(new LineupPlayer(id, home ? homeName : awayName, home, text(p, "jersey_number"),
                    title(text(p, "first_name") + " " + text(p, "last_name")), false, c[0], c[1], c[2], c[3],
                    0, 0, 0, 0, title(text(p, "last_name")), false));
        }
        return new Sheet(homeId, awayId, events, lineups, unmatched);
    }

    /**
     * Estadísticas por jugador de un torneo, desde sus partidos terminados: partidos jugados (figura en la planilla),
     * goles, azules y rojas (de los eventos que cruzaron con la planilla).
     */
    public static List<PlayerStats> playerStats(List<Sheet> sheets) {
        Map<String, PlayerStats> byPlayer = new LinkedHashMap<>();   // "jugador|club"
        for (Sheet s : sheets) {
            for (LineupPlayer p : s.lineups()) {
                int club = p.home() ? s.homeClubId() : s.awayClubId();
                byPlayer.merge(p.playerId() + "|" + club,
                        new PlayerStats(p.playerId(), club, code(p.team()), club, p.name(), p.goals(), 1, 0, 0, 0, 0, 0,
                                p.blueCards(), p.redCards()),
                        (a, b) -> new PlayerStats(a.playerId(), a.teamId(), a.teamCode(), a.clubId(), a.name(),
                                a.goals() + b.goals(), a.games() + b.games(), 0, 0, 0, 0, 0,
                                a.blueCards() + b.blueCards(), a.redCards() + b.redCards()));
            }
        }
        return List.copyOf(byPlayer.values());
    }

    private static EventType eventType(String type) {
        return switch (type) {
            case "goal" -> EventType.GOAL;
            case "yellow_card" -> EventType.YELLOW_CARD;
            case "blue_card" -> EventType.BLUE_CARD;
            case "red_card" -> EventType.RED_CARD;
            case "foul", "direct_foul", "advantage_foul" -> EventType.FOUL;
            default -> null;
        };
    }

    private static int period(String period) {
        return switch (period) {
            case "primer_tiempo" -> 1;
            case "segundo_tiempo" -> 2;
            default -> 0;
        };
    }

    /** "2026-03-13T03:00:00.000Z" → el día en Argentina (13/03/2026); null si no viene. */
    static LocalDate date(String utc) {
        if (utc.isEmpty()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(utc).atZoneSameInstant(ARGENTINA).toLocalDate();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static LocalTime time(String s) {
        if (s.isEmpty()) {
            return null;
        }
        try {
            return LocalTime.parse(s);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** Sin acentos, en mayúsculas y con un espacio entre palabras: "Unión  Vecinal" → "UNION VECINAL". */
    static String norm(String s) {
        String plain = Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return String.join(" ", plain.toUpperCase(Locale.ROOT).trim().split("\\s+"));
    }

    /** La sigla del club: no hay una en la API, va el nombre en mayúsculas ("UVT", "ABERASTAIN", "UNIÓN"). */
    static String code(String name) {
        return String.join(" ", name.trim().toUpperCase(Locale.ROOT).split("\\s+"));
    }

    /** "LUIS IGNACIO ALANIZ" → "Luis Ignacio Alaniz". */
    static String title(String s) {
        StringBuilder sb = new StringBuilder();
        for (String w : s.trim().toLowerCase(Locale.ROOT).split("\\s+")) {
            if (!w.isEmpty()) {
                sb.append(sb.isEmpty() ? "" : " ").append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
            }
        }
        return sb.toString();
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isNull() || v.isMissingNode() ? "" : v.asText().trim();
    }

    private static JsonNode array(String json) {
        if (json == null || json.isBlank()) {
            return JSON.createArrayNode();
        }
        JsonNode node = tree(json);
        return node.isArray() ? node : JSON.createArrayNode();
    }

    private static JsonNode tree(String json) {
        try {
            return JSON.readTree(json);
        } catch (JsonProcessingException e) {
            throw new PsjClient.PsjException("Respuesta inválida de gestiondepsj: " + e.getOriginalMessage());
        }
    }
}
