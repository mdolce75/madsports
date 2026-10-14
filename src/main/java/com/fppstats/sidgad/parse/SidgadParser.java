package com.fppstats.sidgad.parse;

import com.fppstats.sidgad.model.Competition;
import com.fppstats.sidgad.model.Models.EventType;
import com.fppstats.sidgad.model.Models.LineupPlayer;
import com.fppstats.sidgad.model.Models.Match;
import com.fppstats.sidgad.model.Models.MatchEvent;
import com.fppstats.sidgad.model.Models.PlayerStats;
import com.fppstats.sidgad.model.Models.StandingRow;
import com.fppstats.sidgad.model.Models.TeamRef;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Convierte los fragmentos HTML de server2.sidgad.es en modelos.
 * Cada método documenta el fragmento que espera; si SIDGAD cambia el markup, los tests
 * con fixtures en {@code src/test/resources/fixtures} fallan primero.
 */
public final class SidgadParser {

    private static final Pattern CLUB_LOGO = Pattern.compile("logos_clubes/(\\d+)");
    private static final Pattern CLASS_NUM = Pattern.compile("^(?:id_fede|temp|team|teamid)_(\\d+)$");
    private static final Pattern SCORE = Pattern.compile("(\\d+)\\s*-\\s*(\\d+)");
    private static final Pattern ROUND = Pattern.compile("(\\d+)");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private SidgadParser() {
    }

    /**
     * Portal (página principal): selector de temporadas {@code a.select_temporada[id_temp][temp_name]},
     * p. ej. {@code id_temp="40" temp_name="2026"}. Devuelve año → id de temporada.
     */
    public static Map<Integer, Integer> seasons(String html) {
        Map<Integer, Integer> byYear = new java.util.TreeMap<>();
        for (Element a : Jsoup.parse(html).select("a.select_temporada[id_temp][temp_name]")) {
            int year = parseInt(a.attr("temp_name"));
            int id = parseInt(a.attr("id_temp"));
            if (year > 0 && id > 0) {
                byYear.put(year, id);
            }
        }
        return byYear;
    }

    /** {@code cap_ls_1.php}: anclas {@code a.idc_master} + inputs {@code teams_array_<idc>}. */
    public static List<Competition> catalog(String html) {
        Document doc = Jsoup.parse(html);
        List<Competition> result = new ArrayList<>();
        for (Element a : doc.select("a.idc_master")) {
            int id = parseInt(a.id());
            if (id <= 0) {
                continue;
            }
            int fede = classNumber(a, "id_fede_");
            int temp = classNumber(a, "temp_");
            Element input = doc.getElementById("teams_array_" + id);
            List<TeamRef> teams = input == null ? List.of() : teamsArray(input.val());
            result.add(Competition.of(id, clean(a.attr("name")), fede, temp, teams, periodMinutes(a.attr("config_params"))));
        }
        return result;
    }

    private static final Pattern PERIOD_MINUTES = Pattern.compile("(?:^|;)tpo_reg:(\\d+)");

    /** Minutos por período: {@code tpo_reg:20} dentro de config_params (0 si no está). */
    static int periodMinutes(String configParams) {
        Matcher m = PERIOD_MINUTES.matcher(configParams == null ? "" : configParams);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private static final Pattern PERIOD = Pattern.compile("P(\\d+)");
    private static final Pattern CLOCK = Pattern.compile("(\\d{1,3}):(\\d{2})");

    /**
     * {@code cap_gr_<idp>_1.php}: acta del partido. Cada evento es un {@code tr} con
     * {@code .game_view_indcidencias_period} (P1/P2), {@code .game_view_incidencias_time} (minuto transcurrido
     * en el período) y la descripción en {@code .evento_*} (texto en español en {@code .lang_es}).
     * Se toman goles, tarjetas (amarilla, azul, roja) y faltas de equipo ("FALTA"); "FALTA DIRECTA PARA" es el
     * tiro que se concede, no una falta, y no se cuenta.
     * Si SIDGAD no publicó el acta responde "File not found." y se devuelve una lista vacía.
     */
    public static List<MatchEvent> gameEvents(String html) {
        List<MatchEvent> events = new ArrayList<>();
        if (html == null || html.isBlank() || html.startsWith("File not found")) {
            return events;
        }
        Document doc = Jsoup.parse(html);
        for (Element periodDiv : doc.select("div.game_view_indcidencias_period")) {
            Element tr = periodDiv.closest("tr");
            Element label = tr == null ? null : tr.selectFirst("[class^=evento_] .lang_es");
            EventType type = label == null ? null : eventType(label.text());
            Element time = tr == null ? null : tr.selectFirst(".game_view_incidencias_time");
            if (type == null || time == null) {
                continue;
            }
            Matcher clock = CLOCK.matcher(time.text());
            if (!clock.find()) {
                continue;
            }
            Matcher p = PERIOD.matcher(periodDiv.text());
            Element player = tr.selectFirst("a[team_id]");
            Element code = tr.selectFirst("td.stats_table .texto_gris_11");
            events.add(new MatchEvent(p.find() ? Integer.parseInt(p.group(1)) : 0,
                    Integer.parseInt(clock.group(1)), Integer.parseInt(clock.group(2)), type,
                    player == null ? 0 : parseInt(player.attr("team_id")), clubId(tr),
                    code == null ? "" : clean(code.text()),
                    player == null ? "" : clean(player.text()),
                    player == null ? 0 : parseInt(player.attr("id_player"))));
        }
        return events;
    }

    /**
     * Jugadores de cada equipo en un partido ({@code cap_gr_<idp>_1.php}): una tabla
     * {@code competiciones_tabla_basic} por equipo, con el nombre en {@code .nombre_equipo_thickbox_stats} y una fila
     * por jugador citado con su {@code id_player}. Columnas: Nº, portero, titular, 5 inicial, bandera, nombre, goles,
     * asistencias, penales, faltas directas, faltas cometidas, faltas recibidas, amarillas, azules y rojas.
     * La primera planilla es la del local y la segunda la del visitante. Lista vacía si el acta no está publicada.
     */
    public static List<LineupPlayer> lineups(String html) {
        List<LineupPlayer> players = new ArrayList<>();
        if (html == null || html.isBlank() || html.startsWith("File not found")) {
            return players;
        }
        Set<String> seen = new HashSet<>();
        int side = 0;
        for (Element table : Jsoup.parse(html).select("table.competiciones_tabla_basic")) {
            Element teamName = table.selectFirst(".nombre_equipo_thickbox_stats");
            if (teamName == null || side >= 2) {
                continue;
            }
            boolean home = side++ == 0;
            String team = clean(teamName.text());
            for (Element tr : table.select("tbody > tr")) {
                Element link = tr.selectFirst("[id_player]");
                List<Element> tds = tr.children();
                if (link == null || tds.size() < 15) {
                    continue;
                }
                int id = parseInt(link.attr("id_player"));
                int[] pen = fraction(clean(tds.get(8).text()));
                int[] fd = fraction(clean(tds.get(9).text()));
                if (id <= 0 || !seen.add(team + "|" + id)) {
                    continue;
                }
                players.add(new LineupPlayer(id, team, home, clean(tds.get(0).text()), lineupName(link),
                        "5".equals(clean(tds.get(3).text())),
                        parseInt(clean(tds.get(6).text())), parseInt(clean(tds.get(12).text())),
                        parseInt(clean(tds.get(13).text())), parseInt(clean(tds.get(14).text())),
                        pen[0], pen[1], fd[0], fd[1]));
            }
        }
        return players;
    }

    /** "PEREZ BARBOSA <span>JUAN IGNACIO</span>" -> "Juan Ignacio Perez Barbosa" (sin la marca de capitán). */
    private static String lineupName(Element link) {
        Element given = link.selectFirst(".texto_gris_11");
        String g = given == null ? "" : given.text();
        return playerName((link.ownText() + ", " + g).replace("(C)", ""));
    }

    private static EventType eventType(String label) {
        return switch (clean(label).toUpperCase()) {
            case "GOL" -> EventType.GOAL;
            case "TARJETA AMARILLA" -> EventType.YELLOW_CARD;
            case "TARJETA AZUL" -> EventType.BLUE_CARD;
            case "TARJETA ROJA" -> EventType.RED_CARD;
            case "FALTA" -> EventType.FOUL;
            default -> null;
        };
    }

    /** Formato: {@code "74_3.png,4660,CAVS A,VELEZ A ;74_3.png,4659,CAVS B,VELEZ B;..."}. */
    static List<TeamRef> teamsArray(String value) {
        List<TeamRef> teams = new ArrayList<>();
        for (String item : value.split(";")) {
            String[] p = item.split(",", 4);
            if (p.length < 4) {
                continue;
            }
            int teamId = parseInt(p[1]);
            int clubId = parseInt(p[0].replaceAll("[^0-9].*$", ""));
            if (teamId > 0) {
                teams.add(new TeamRef(teamId, clubId, clean(p[2]), clean(p[3]), p[0].trim()));
            }
        }
        return teams;
    }

    /** {@code cap_clasif_idc_<idc>_1.php}: una tabla por grupo, el nombre del grupo en el primer th. */
    public static List<StandingRow> standings(String html) {
        Document doc = Jsoup.parse(html);
        List<StandingRow> rows = new ArrayList<>();
        for (Element table : doc.select("table.tabla_standard")) {
            Element th = table.selectFirst("thead th");
            String group = th == null ? "" : clean(th.ownText());
            for (Element tr : table.select("tbody tr")) {
                Elements tds = tr.children();
                if (tds.size() < 11) {
                    continue;
                }
                int[] n = new int[8];
                for (int i = 0; i < 8; i++) {
                    n[i] = parseInt(tds.get(3 + i).text());
                }
                rows.add(new StandingRow(group, parseInt(tds.get(0).text()), clubId(tr),
                        clean(textOf(tds.get(2), ".no_mobile")), clean(textOf(tds.get(2), ".mobile")),
                        n[0], n[1], n[2], n[3], n[4], n[5], n[6], n[7]));
            }
        }
        return rows;
    }

    private static final Pattern REPORT_DATE = Pattern.compile("Report Created on (\\d{2})-(\\d{2})-(\\d{4})");

    /**
     * Fecha en que SIDGAD generó el fragmento ("Report Created on 03-05-2026 ..."). Importa porque la
     * clasificación de un torneo puede quedar congelada mientras el calendario sigue actualizándose.
     */
    public static LocalDate reportDate(String html) {
        Matcher m = REPORT_DATE.matcher(html);
        return m.find() ? LocalDate.of(Integer.parseInt(m.group(3)), Integer.parseInt(m.group(2)),
                Integer.parseInt(m.group(1))) : null;
    }

    /**
     * {@code cap_cal_idc_<idc>_1.php}: {@code thead.head_jornada} marca la jornada; cada partido es un
     * {@code tr.team_class} con clases {@code team_<local> team_<visitante>}.
     */
    public static List<Match> calendar(String html) {
        Document doc = Jsoup.parse(html);
        List<Match> matches = new ArrayList<>();
        int round = 0;
        for (Element el : doc.select("thead.head_jornada, tr.team_class")) {
            if (el.tagName().equals("thead")) {
                Matcher m = ROUND.matcher(el.text());
                round = m.find() ? Integer.parseInt(m.group(1)) : round + 1;
                continue;
            }
            List<Integer> teamIds = el.classNames().stream()
                    .filter(c -> c.matches("team_\\d+")).map(c -> parseInt(c.substring(5))).toList();
            List<Element> names = el.select(".nombre_junto_logo");
            List<Element> logos = el.select("img.team_logo");
            if (teamIds.size() < 2 || names.size() < 2) {
                continue;
            }
            Elements tds = el.children();
            LocalDate date = parseDate(tds.size() > 1 ? tds.get(1).text() : "");
            LocalTime time = parseTime(tds.size() > 2 ? tds.get(2).text() : "");
            String venue = tds.size() > 3 ? clean(tds.get(3).text()) : "";
            Element scoreTd = el.selectFirst("td.web_link_td[style*=center]");
            Integer hg = null;
            Integer ag = null;
            if (scoreTd != null) {
                Matcher s = SCORE.matcher(scoreTd.text());
                if (s.find()) {
                    hg = Integer.parseInt(s.group(1));
                    ag = Integer.parseInt(s.group(2));
                }
            }
            Element report = el.selectFirst("[idp]");
            matches.add(new Match(report == null ? 0 : parseInt(report.attr("idp")), round, date, time, venue,
                    teamIds.get(0), logos.size() > 0 ? clubIdFromSrc(logos.get(0).attr("src")) : 0,
                    clean(names.get(0).text()),
                    teamIds.get(1), logos.size() > 1 ? clubIdFromSrc(logos.get(1).attr("src")) : 0,
                    clean(names.get(1).text()), hg, ag));
        }
        return matches;
    }

    /**
     * {@code cap_stats_1_<idc>.php}: una fila {@code tr.fila_stats_player} por jugador con 13 columnas
     * {@code .stats_table*}: G, PJ, Gpp, As, App, Pen, Pen%, FD, FD%, Az, Azpp, Rj, Rjpp.
     */
    public static List<PlayerStats> playerStats(String html) {
        Document doc = Jsoup.parse(html);
        List<PlayerStats> players = new ArrayList<>();
        for (Element tr : doc.select("tr.fila_stats_player")) {
            Element link = tr.selectFirst("a[id_player]");
            Elements cells = tr.select("td.stats_table_special, td.stats_table");
            if (link == null || cells.size() < 13) {
                continue;
            }
            int teamId = parseInt(link.attr("team_id"));
            if (teamId == 0) {
                teamId = classNumber(tr, "teamid_");
            }
            Element code = tr.selectFirst("td.no_mobile span");
            int[] pen = fraction(cells.get(5).text());
            int[] fd = fraction(cells.get(7).text());
            players.add(new PlayerStats(parseInt(link.attr("id_player")), teamId,
                    code == null ? "" : clean(code.text()), clubId(tr), playerName(link.attr("player_name")),
                    parseInt(cells.get(0).text()), parseInt(cells.get(1).text()), parseInt(cells.get(3).text()),
                    pen[0], pen[1], fd[0], fd[1],
                    parseInt(cells.get(9).text()), parseInt(cells.get(11).text())));
        }
        return players;
    }

    /** "GRANATO, MARTINA LORENA" -> "Martina Lorena Granato". */
    static String playerName(String raw) {
        String s = clean(raw);
        int comma = s.indexOf(',');
        String full = comma < 0 ? s : (s.substring(comma + 1).trim() + " " + s.substring(0, comma).trim());
        StringBuilder sb = new StringBuilder();
        for (String w : full.toLowerCase().split("\\s+")) {
            if (!w.isEmpty()) {
                sb.append(sb.isEmpty() ? "" : " ").append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
            }
        }
        return sb.toString();
    }

    private static int[] fraction(String text) {
        String[] p = text.trim().split("/");
        if (p.length != 2) {
            return new int[]{0, 0};
        }
        return new int[]{parseInt(p[0]), parseInt(p[1])};
    }

    private static int clubId(Element row) {
        Element img = row.selectFirst("img[src*=logos_clubes]");
        return img == null ? 0 : clubIdFromSrc(img.attr("src"));
    }

    static int clubIdFromSrc(String src) {
        Matcher m = CLUB_LOGO.matcher(src);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private static int classNumber(Element el, String prefix) {
        for (String c : el.classNames()) {
            if (c.startsWith(prefix) && CLASS_NUM.matcher(c).matches()) {
                return parseInt(c.substring(prefix.length()));
            }
        }
        return 0;
    }

    private static String textOf(Element parent, String selector) {
        Element e = parent.selectFirst(selector);
        return e == null ? parent.text() : e.text();
    }

    private static LocalDate parseDate(String s) {
        try {
            return LocalDate.parse(s.trim(), DATE);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static LocalTime parseTime(String s) {
        try {
            return LocalTime.parse(s.trim());
        } catch (RuntimeException e) {
            return null;
        }
    }

    static int parseInt(String s) {
        if (s == null) {
            return 0;
        }
        String digits = s.trim();
        if (!digits.matches("-?\\d+")) {
            return 0;
        }
        return Integer.parseInt(digits);
    }

    /** Recorta, colapsa espacios y quita el carácter de reemplazo que SIDGAD deja en algunas Ñ. */
    static String clean(String s) {
        return s == null ? "" : s.replace('�', 'Ñ').replace(' ', ' ').replaceAll("\\s+", " ").trim();
    }
}
