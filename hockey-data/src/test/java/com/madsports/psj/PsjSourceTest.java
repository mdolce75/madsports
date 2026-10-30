package com.madsports.psj;

import com.madsports.sidgad.SheetStore;
import com.madsports.sidgad.model.Competition;
import com.madsports.sidgad.model.Models.EventType;
import com.madsports.sidgad.model.Models.LineupPlayer;
import com.madsports.sidgad.model.Models.Match;
import com.madsports.sidgad.model.Models.MatchEvent;
import com.madsports.sidgad.model.Models.PlayerStats;
import com.madsports.sidgad.model.Models.StandingRow;
import com.madsports.source.DataSource;
import com.madsports.source.GameSheet;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La fuente de gestiondepsj con respuestas reales guardadas el 30/10/2026 ({@code psj/}): de la FSP, Primera A1
 * Apertura 2026 (200004) con sus 14 fechas y dos partidos completos (1000026 Olimpia-Lomas y 1000414
 * Aberastain-Olimpia); de la UEHP, un torneo sin año en el nombre (200015).
 */
class PsjSourceTest {

    static final PsjProperties PROPS = new PsjProperties("http://localhost/api/public/", "http://localhost/",
            Duration.ZERO, 3, Duration.ofMinutes(5), Duration.ofMinutes(5), Duration.ofSeconds(1));

    /** Lo que pidió el cliente y lo que guardó. */
    final List<String> requested = new ArrayList<>();
    final Map<Integer, String> stored = new HashMap<>();

    final PsjClient client = new PsjClient(PROPS) {
        @Override
        protected String download(String path) {
            synchronized (requested) {
                requested.add(path);
            }
            return fixture(path);
        }
    };
    final PsjSource source = new PsjSource(client);

    {
        client.setSheetStore(new SheetStore() {
            @Override
            public Optional<String> get(int idp) {
                return Optional.ofNullable(stored.get(idp));
            }

            @Override
            public void put(int idp, String html) {
                stored.put(idp, html);
            }
        });
    }

    /** El fixture de una ruta de la API ("tournaments/200004/standings"); 404 si no está guardado. */
    static String fixture(String path) {
        String file = "/psj/" + path.replace('/', '_').replace('?', '_') + ".json";
        try (InputStream in = PsjSourceTest.class.getResourceAsStream(file)) {
            return in == null ? PsjClient.NOT_PUBLISHED : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    Competition apertura() {
        return source.competitions(1, 2026).getFirst();
    }

    @Test
    void tournamentsOfTheFederationInTheYear() {
        List<Competition> fsp = source.competitions(1, 2026);
        assertThat(fsp).hasSize(1);
        Competition c = fsp.getFirst();
        assertThat(c.id()).isEqualTo(200004);
        assertThat(c.name()).isEqualTo("A1 PRIMERA TORNEO APERTURA 2026");
        assertThat(c.seasonId()).isEqualTo(2026);
        assertThat(c.federationId()).isEqualTo(1);
        assertThat(c.periodMinutes()).isEqualTo(25);
        // Los clubes salen de la tabla: el id de equipo es el del club.
        assertThat(c.teams()).hasSize(12).allMatch(t -> t.teamId() == t.clubId());
        assertThat(c.teams()).anySatisfy(t -> {
            assertThat(t.name()).isEqualTo("Aberastain");
            assertThat(t.clubId()).isEqualTo(15);
            assertThat(t.code()).isEqualTo("ABERASTAIN");
            assertThat(t.logo()).startsWith("uploads/clubs/");
        });
        assertThat(source.competitions(1, 2025)).isEmpty();

        // Sin año en el nombre: el de su primer partido.
        assertThat(source.competitions(1000, 2026)).extracting(Competition::id).containsExactly(200015);
    }

    @Test
    void standingsFromTheTable() {
        List<StandingRow> rows = source.standings(apertura()).orElseThrow().rows();
        assertThat(rows).hasSize(12);
        StandingRow first = rows.getFirst();
        assertThat(first.name()).isEqualTo("UVT");
        assertThat(first.position()).isEqualTo(1);
        assertThat(first.points()).isEqualTo(30);
        assertThat(first.group()).isEmpty();
        assertThat(rows).extracting(StandingRow::position).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12);
    }

    @Test
    void calendarOfEveryPhase() {
        List<Match> matches = source.calendar(apertura()).orElseThrow();
        assertThat(matches).extracting(Match::round).contains(1, 14);
        Match first = matches.stream().filter(m -> m.idp() == 1000026).findFirst().orElseThrow();
        // 2026-03-13T03:00Z es el 13/03 a las 0 h en Argentina; el horario viene aparte.
        assertThat(first.date()).isEqualTo(LocalDate.of(2026, 3, 13));
        assertThat(first.time()).isEqualTo(LocalTime.of(21, 45));
        assertThat(first.homeName()).isEqualTo("Olimpia");
        assertThat(first.homeClubId()).isEqualTo(1);
        assertThat(first.homeTeamId()).isEqualTo(1);
        assertThat(first.awayClubId()).isEqualTo(11);
        assertThat(first.homeGoals()).isEqualTo(1);
        assertThat(first.awayGoals()).isEqualTo(1);
        // Definido por penales: el resultado es el del partido (empate).
        Match penalties = matches.stream().filter(m -> m.idp() == 1000879).findFirst().orElseThrow();
        assertThat(penalties.homeGoals()).isZero();
        assertThat(penalties.awayGoals()).isZero();
        assertThat(matches).allMatch(m -> m.homeClubId() > 0 && m.awayClubId() > 0);
    }

    @Test
    void sheetWithLineupsAndEventsOfThePlayers() {
        GameSheet sheet = source.gameSheet(apertura(), 1000026);

        List<LineupPlayer> lineups = sheet.lineups();
        assertThat(lineups).hasSize(20);   // solo jugadores: el cuerpo técnico no va
        assertThat(lineups).filteredOn(LineupPlayer::home).allMatch(p -> p.team().equals("Olimpia"));
        assertThat(lineups).filteredOn(p -> !p.home()).allMatch(p -> p.team().equals("Lomas"));
        assertThat(lineups).anySatisfy(p -> {
            assertThat(p.name()).isEqualTo("Luis Ignacio Alaniz Belloti");
            assertThat(p.surname()).isEqualTo("Alaniz Belloti");
            assertThat(p.number()).isEqualTo("6");
        });

        List<MatchEvent> events = sheet.events();
        // Faltas (31), amarillas (9), azules (2) y goles (2); minuto de juego, corners y lesiones no se analizan.
        assertThat(events).hasSize(44);
        assertThat(events).filteredOn(e -> e.type() == EventType.FOUL).hasSize(31);
        assertThat(events).allMatch(e -> e.period() == 1 || e.period() == 2);
        // Todos los eventos con jugador cruzan con la planilla.
        assertThat(events).filteredOn(e -> !e.player().isEmpty()).allMatch(e -> e.playerId() > 0);
        List<MatchEvent> goals = events.stream().filter(e -> e.type() == EventType.GOAL).toList();
        assertThat(goals).extracting(MatchEvent::teamId).containsExactly(1, 11);
        assertThat(goals.getFirst().minute()).isEqualTo(8);
        LineupPlayer scorer = lineups.stream().filter(p -> p.playerId() == goals.getFirst().playerId())
                .findFirst().orElseThrow();
        assertThat(scorer.goals()).isEqualTo(1);
        assertThat(scorer.home()).isTrue();
    }

    @Test
    void finishedMatchesAreStoredOnce() {
        source.gameSheet(apertura(), 1000026);
        source.gameSheet(apertura(), 1000026);
        assertThat(stored).containsOnlyKeys(-1000026);
        assertThat(requested).containsOnlyOnce("matches/1000026", "matches/1000026/events");

        // Uno que no está publicado queda vacío y no se guarda.
        GameSheet missing = source.gameSheet(apertura(), 999);
        assertThat(missing.events()).isEmpty();
        assertThat(missing.lineups()).isEmpty();
        assertThat(stored).doesNotContainKey(-999);
    }

    @Test
    void playerStatsFromTheSheets() {
        // De los 66 partidos jugados solo hay dos guardados: las estadísticas salen de esos.
        List<PlayerStats> stats = source.playerStats(apertura()).orElseThrow();
        assertThat(stats).allMatch(p -> p.games() >= 1 && p.games() <= 2 && p.teamId() == p.clubId());
        // Olimpia jugó los dos.
        assertThat(stats).filteredOn(p -> p.clubId() == 1).anyMatch(p -> p.games() == 2);
        assertThat(stats).filteredOn(p -> p.clubId() == 15).isNotEmpty();
        int goals = stats.stream().mapToInt(PlayerStats::goals).sum();
        int fromSheets = (int) (source.gameSheet(apertura(), 1000026).events().stream()
                .filter(e -> e.type() == EventType.GOAL && e.playerId() > 0).count()
                + source.gameSheet(apertura(), 1000414).events().stream()
                .filter(e -> e.type() == EventType.GOAL && e.playerId() > 0).count());
        assertThat(goals).isEqualTo(fromSheets);
    }

    @Test
    void sourceAndLogos() {
        assertThat(source.kind()).isEqualTo(DataSource.PSJ);
        assertThat(source.clubLogo("../secret.png")).isNull();
        assertThat(source.clubLogo(null)).isNull();
        assertThat(source.playerSeason(20566, 2026)).isEmpty();
    }

    @Test
    void parserDetails() {
        assertThat(PsjParser.date("2026-05-22T02:59:00.000Z")).isEqualTo(LocalDate.of(2026, 5, 21));
        assertThat(PsjParser.date("")).isNull();
        assertThat(PsjParser.norm("  Unión   Vecinal ")).isEqualTo("UNION VECINAL");
        assertThat(PsjParser.title("GAILLEZ LORENZO ")).isEqualTo("Gaillez Lorenzo");
        assertThat(PsjParser.year("A1 PRIMERA TORNEO APERTURA 2026")).hasValue(2026);
        assertThat(PsjParser.year("Uehp Cadete Entrerriano Femenino")).isEmpty();
    }
}
