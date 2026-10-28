package com.madsports.coach;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.madsports.account.Profile;
import com.madsports.account.SubscriptionPlanRepository;
import com.madsports.account.UserAccount;
import com.madsports.account.UserAccountRepository;
import com.madsports.catalog.CatalogService;
import com.madsports.catalog.CatalogService.ClubView;
import com.madsports.catalog.CatalogService.LevelView;
import com.madsports.catalog.RosterService;
import com.madsports.catalog.RosterService.SquadMember;
import com.madsports.catalog.RosterService.RosterPlayer;
import com.madsports.sidgad.model.Federation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** API de RinkMetrics, con la base H2 en memoria y un reloj que se adelanta a mano. Nada sale de SIDGAD. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:planilla;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class CoachControllerTest {

    static final ZoneId BA = ZoneId.of("America/Argentina/Buenos_Aires");
    static final Instant BEGINNING = Instant.parse("2026-10-16T18:00:00Z");

    /** Reloj de prueba: arranca el 16/10/2026 a las 15:00 (Buenos Aires) y avanza con {@link #advance}. */
    static class TestClock extends Clock {
        Instant now = BEGINNING;

        void advance(int seconds) {
            now = now.plusSeconds(seconds);
        }

        @Override public ZoneId getZone() { return BA; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @TestConfiguration
    static class Config {
        @Bean @Primary
        TestClock testClock() {
            return new TestClock();
        }
    }

    static Map<String, Object> action(int playerId, String kind, String outcome, int period, int atSecond) {
        Map<String, Object> m = new HashMap<>(Map.of("id", UUID.randomUUID().toString(),
                "playerId", playerId, "kind", kind, "period", period, "atSecond", atSecond));
        if (outcome != null) {
            m.put("outcome", outcome);
        }
        return m;
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserAccountRepository users;
    @Autowired SubscriptionPlanRepository subscriptions;
    @Autowired LiveMatchRepository matches;
    @Autowired MatchActionRepository actions;
    @Autowired TestClock clock;
    @Autowired SquadService squads;

    /** El plantel ya no sale de SIDGAD: si alguien lo pidiera, el test lo detecta. */
    @MockitoBean RosterService roster;
    /** El club del DT en SIDGAD (sus categorías): solo lo piden los equipos armados desde SIDGAD. */
    @MockitoBean CatalogService catalog;

    /** Equipo "Primera A" de profe y sus dos jugadores. */
    long primera;
    int goalkeeper;
    int forward;

    @BeforeEach
    void setUp() throws Exception {
        actions.deleteAll();
        matches.deleteAll();
        users.deleteAll();
        clock.now = BEGINNING;
        coach("profe");
        coach("otro");
        primera = team("profe", "Primera A");
        goalkeeper = player("profe", "Juan", "Arquero", "1", true, primera);
        forward = player("profe", "Pedro", "Delantero", "7", false, primera);
    }

    void coach(String name) {
        coach(name, "COACH_MONTHLY");
    }

    void coach(String name, String plan) {
        UserAccount u = new UserAccount(name, "x", name + "@example.com", "Ana", "Pérez", 74, "VELEZ", "CAVS",
                EnumSet.of(Profile.COACHFPP), Instant.now());
        u.subscribe(subscriptions.findByCode(plan).orElseThrow(), LocalDate.of(2026, 9, 15));
        users.save(u);
    }

    static RequestPostProcessor como(String username) {
        return user(username).authorities(() -> "COACHFPP");
    }

    ResultActions send(MockHttpServletRequestBuilder req, String username, Object body) throws Exception {
        return mvc.perform(req.with(como(username)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    long team(String username, String name) throws Exception {
        String body = send(post("/api/coach/teams"), username, Map.of("name", name))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("id").asLong();
    }

    int player(String username, String name, String lastName, String number, boolean isGoalkeeper, Long... teams)
            throws Exception {
        String body = send(post("/api/coach/players"), username, Map.of("name", name, "lastName", lastName,
                "number", number, "goalkeeper", isGoalkeeper, "teams", List.of(teams)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("id").asInt();
    }

    Map<String, Object> match(String opponent) {
        return Map.of("team", primera, "opponent", opponent, "date", "2026-10-18", "home", true);
    }

    long create(String username, String opponent) throws Exception {
        String body = send(post("/api/coach/matches"), username, match(opponent))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("id").asLong();
    }

    @Test
    void teamCrudWithItsRecord() throws Exception {
        long infantil = team("profe", "Infantil");
        send(post("/api/coach/teams"), "profe", Map.of("name", " primera   a ")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Ya tenés un equipo llamado primera a."));
        send(post("/api/coach/teams"), "profe", Map.of("name", " ")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Escribí el nombre del equipo."));
        // El plan mensual permite hasta 3 equipos.
        team("profe", "Juveniles");
        send(post("/api/coach/teams"), "profe", Map.of("name", "Cuarto")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Tu plan DT mensual permite hasta 3 equipos. Para sumar otro, borrá "
                        + "uno sin partidos."));
        send(put("/api/coach/teams/" + infantil), "profe", Map.of("name", "Infantil B")).andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Infantil B"));

        // Balance: un partido ganado y finalizado; otro sin terminar no cuenta.
        long id = create("profe", "HURACAN");
        send(post("/api/coach/matches/" + id + "/actions"), "profe", List.of(action(forward, "SHOT", "GOAL", 1, 30)))
                .andExpect(status().isOk());
        for (String a : List.of("START", "END_PERIOD", "START", "END_PERIOD")) {
            send(post("/api/coach/matches/" + id + "/clock"), "profe", Map.of("action", a)).andExpect(status().isOk());
        }
        create("profe", "GEBA");
        mvc.perform(get("/api/coach/teams").with(como("profe"))).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name").value(contains("Primera A", "Infantil B", "Juveniles")))
                .andExpect(jsonPath("$[0].played").value(1))
                .andExpect(jsonPath("$[0].won").value(1))
                .andExpect(jsonPath("$[0].drawn").value(0))
                .andExpect(jsonPath("$[0].lost").value(0));

        // Un equipo con partidos no se borra; uno sin partidos, sí.
        mvc.perform(delete("/api/coach/teams/" + primera).with(como("profe")).with(csrf())).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Primera A tiene 2 partidos cargados: no se puede borrar."));
        mvc.perform(delete("/api/coach/teams/" + infantil).with(como("profe")).with(csrf())).andExpect(status().isNoContent());
        // Los equipos de un DT no son de otro.
        mvc.perform(get("/api/coach/teams").with(como("otro"))).andExpect(jsonPath("$", hasSize(0)));
        send(put("/api/coach/teams/" + primera), "otro", Map.of("name", "Mío")).andExpect(status().isNotFound());
    }

    @Test
    void simultaneousCreationsByTheSameCoachDoNotCollide() throws Exception {
        // Las altas toman el id siguiente al último del DT: las que chocan se reintentan, ninguna termina en error.
        UserAccount coachUser = users.findByUsername("profe").orElseThrow();
        int n = 6;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<SquadService.SquadPlayer>> creations = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            var data = new SquadService.PlayerData("Suplente" + i, "Ríos", null, false, List.of(primera));
            creations.add(pool.submit(() -> {
                start.await();
                return squads.addPlayer(coachUser, data);
            }));
        }
        start.countDown();
        Set<Integer> ids = new HashSet<>();
        for (var addPlayer : creations) {
            ids.add(addPlayer.get(30, TimeUnit.SECONDS).id());
        }
        pool.shutdown();
        assertThat(ids).hasSize(n).doesNotContain(goalkeeper, forward);
        assertThat(squads.squad(coachUser, primera)).hasSize(n + 2);
    }

    @Test
    void playerCrudInOneOrSeveralTeams() throws Exception {
        long infantil = team("profe", "Infantil");
        send(post("/api/coach/players"), "profe", Map.of("name", "Sin", "lastName", "Equipo"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Asigná el jugador a un equipo."));
        // Un jugador en los dos equipos.
        int doble = player("profe", "Tomás", "Doble", "09", false, primera, infantil);
        assertThat(doble).isGreaterThanOrEqualTo(900_000_000);
        // El número no se repite dentro de un equipo, pero sí entre equipos distintos.
        send(post("/api/coach/players"), "profe", Map.of("name", "Otro", "lastName", "Siete", "number", "7",
                "teams", List.of(primera))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("El número 7 ya es de Pedro Delantero en ese equipo."));
        player("profe", "Lucas", "Siete", "7", false, infantil);
        send(post("/api/coach/players"), "profe", Map.of("name", "A", "lastName", "B", "number", "1234",
                "teams", List.of(primera))).andExpect(status().isBadRequest());

        mvc.perform(get("/api/coach/squad").param("team", String.valueOf(primera)).with(como("profe")))
                .andExpect(jsonPath("$[*].name").value(contains("Juan Arquero", "Pedro Delantero", "Tomás Doble")))
                .andExpect(jsonPath("$[2].number").value("9"));
        mvc.perform(get("/api/coach/squad").param("team", String.valueOf(infantil)).with(como("profe")))
                .andExpect(jsonPath("$[*].name").value(contains("Lucas Siete", "Tomás Doble")));
        mvc.perform(get("/api/coach/players").with(como("profe"))).andExpect(jsonPath("$", hasSize(4)))
                .andExpect(jsonPath("$[0].name").value("Juan Arquero"))
                .andExpect(jsonPath("$[2].name").value("Tomás Doble"))
                .andExpect(jsonPath("$[2].teams", hasSize(2)));

        // Lo saca de Infantil y le corrige el nombre: el informe del partido muestra el nombre nuevo.
        long id = create("profe", "HURACAN");
        send(post("/api/coach/matches/" + id + "/actions"), "profe", List.of(action(doble, "SHOT", "GOAL", 1, 30)))
                .andExpect(status().isOk());
        send(put("/api/coach/players/" + doble), "profe", Map.of("name", "Tomás José", "lastName", "Doble",
                "number", "10", "teams", List.of(primera))).andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Tomás José Doble"))
                .andExpect(jsonPath("$.teams", hasSize(1)));
        mvc.perform(get("/api/coach/squad").param("team", String.valueOf(infantil)).with(como("profe")))
                .andExpect(jsonPath("$[*].name").value(contains("Lucas Siete")));
        mvc.perform(get("/api/coach/matches/" + id + "/report").with(como("profe")))
                .andExpect(jsonPath("$.players[*].name").value(contains("Tomás José Doble")));

        // Baja: ya no se le cargan acciones; las suyas quedan en el informe.
        mvc.perform(delete("/api/coach/players/" + doble).with(como("profe")).with(csrf())).andExpect(status().isNoContent());
        send(post("/api/coach/matches/" + id + "/actions"), "profe", List.of(action(doble, "FOUL", "COMMITTED", 1, 50)))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/coach/matches/" + id + "/report").with(como("profe")))
                .andExpect(jsonPath("$.totals.goals").value(1));
        mvc.perform(delete("/api/coach/players/" + doble).with(como("profe")).with(csrf())).andExpect(status().isNotFound());
        // Un jugador no se puede poner en el equipo de otro DT.
        long foreign = team("otro", "Ajeno");
        send(post("/api/coach/players"), "profe", Map.of("name", "X", "lastName", "Y", "teams", List.of(foreign)))
                .andExpect(status().isNotFound());
        send(put("/api/coach/players/" + forward), "otro", Map.of("name", "X", "lastName", "Y",
                "teams", List.of(foreign))).andExpect(status().isNotFound());
        verifyNoInteractions(roster);
    }

    @Test
    void theCoachOfAnotherFederationLoadsTeamsAndPlayersByHand() throws Exception {
        UserAccount u = new UserAccount("tandil", "x", "tandil@example.com", null, null, 0, "Club Atlético Tandil", "",
                EnumSet.of(Profile.COACHOTR), Instant.now());
        u.subscribe(subscriptions.findByCode("COACH_FREE").orElseThrow(), LocalDate.of(2026, 10, 1));
        users.save(u);
        RequestPostProcessor tandil = user("tandil").authorities(() -> "COACHOTR");

        // Sin SIDGAD: no hay categorías del club de donde armar un equipo, ni se le pregunta a SIDGAD.
        mvc.perform(get("/api/coach/club/categories").with(tandil)).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(post("/api/coach/teams").with(tandil).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", "Primera", "category", "PRIMERA",
                                "branch", "MASCULINO"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Esa categoría no es de tu club."));

        // El equipo, con un nombre; los jugadores, a mano.
        long team = team("tandil", "Primera");
        int id = player("tandil", "Juan", "Pérez", "10", false, team);
        mvc.perform(get("/api/coach/squad").param("team", String.valueOf(team)).with(tandil))
                .andExpect(jsonPath("$[*].name").value(contains("Juan Pérez")));

        // Sus partidos quedan como de otra federación, con su club.
        String body = mvc.perform(post("/api/coach/matches").with(tandil).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("team", team,
                                "opponent", "AYACUCHO", "date", "2026-10-18", "home", true))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long match = json.readTree(body).get("id").asLong();
        assertThat(matches.findById(match).orElseThrow().getFederation()).isEqualTo("OTR");
        mvc.perform(post("/api/coach/matches/" + match + "/actions").with(tandil).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(List.of(action(id, "SHOT", "GOAL", 1, 30)))))
                .andExpect(status().isOk());
        mvc.perform(get("/api/coach/matches/" + match + "/report").with(tandil))
                .andExpect(jsonPath("$.title").value(containsString("Club Atlético Tandil")))
                .andExpect(jsonPath("$.totals.goals").value(1));
        verifyNoInteractions(roster, catalog);
    }

    /** Vélez en SIDGAD: Primera Masculino e Infantil B en las dos ramas; el plantel de Infantil B Femenino y Primera. */
    void clubInSidgad() {
        when(catalog.defaultYear()).thenReturn(2026);
        when(catalog.club(Federation.FPP, 74, 2026)).thenReturn(new ClubView(74, "CAVS", "VELEZ", List.of(
                new LevelView("PRIMERA", "Primera", List.of("MASCULINO")),
                new LevelView("INFANTIL:B", "Infantil B", List.of("MASCULINO", "FEMENINO")))));
        when(roster.players(Federation.FPP, 74, 2026, "INFANTIL:B", "FEMENINO")).thenReturn(List.of(
                new RosterPlayer(501, "Ana Gómez", "Ana", "Gómez", "Infantil B Femenino"),
                new RosterPlayer(502, "Bea Ruiz", "Bea", "Ruiz", "Infantil B Femenino"),
                new RosterPlayer(503, "Carla Díaz", "Carla", "Díaz", "Infantil B Femenino")));
        when(roster.squad(Federation.FPP, 74, 2026, "INFANTIL:B", "FEMENINO")).thenReturn(List.of(
                new SquadMember(501, "07", "Ana Gómez", false), new SquadMember(502, "1", "Bea Ruiz", true),
                new SquadMember(503, "07", "Carla Díaz", false)));
        when(roster.players(Federation.FPP, 74, 2026, "PRIMERA", "MASCULINO")).thenReturn(List.of(
                new RosterPlayer(501, "Ana Gómez", "Ana", "Gómez", "Primera Masculino"),
                new RosterPlayer(504, "Dante Paz", "Dante", "Paz", "Primera Masculino")));
        when(roster.squad(Federation.FPP, 74, 2026, "PRIMERA", "MASCULINO")).thenReturn(List.of(
                new SquadMember(504, "10", "Dante Paz", false)));
        // Infantil B Masculino no tiene jugadores en SIDGAD: no se ofrece.
        when(roster.withPlayers(eq(Federation.FPP), any(), eq(2026))).thenReturn(new ClubView(74, "CAVS", "VELEZ", List.of(
                new LevelView("PRIMERA", "Primera", List.of("MASCULINO")),
                new LevelView("INFANTIL:B", "Infantil B", List.of("FEMENINO")))));
    }

    @Test
    void teamFromAClubCategoryWithItsSidgadSquad() throws Exception {
        clubInSidgad();
        mvc.perform(get("/api/coach/club/categories").with(como("profe"))).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name").value(contains("Primera Masculino", "Infantil B Femenino")))
                .andExpect(jsonPath("$[1].key").value("INFANTIL:B"))
                .andExpect(jsonPath("$[1].branch").value("FEMENINO"));
        // Su plantel en SIDGAD, por apellido, con el número más usado ("07" -> "7") y los arqueros.
        mvc.perform(get("/api/coach/club/squad").param("category", "INFANTIL:B").param("branch", "FEMENINO")
                        .with(como("profe"))).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].lastName").value(contains("Díaz", "Gómez", "Ruiz")))
                .andExpect(jsonPath("$[1].number").value("7"))
                .andExpect(jsonPath("$[2].goalkeeper").value(true));
        mvc.perform(get("/api/coach/club/squad").param("category", "JUVENIL").param("branch", "MASCULINO")
                .with(como("profe"))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Esa categoría no es de tu club."));

        // El equipo entra con todo el plantel menos Carla, que queda "no incluida". Sin nombre: el de la categoría.
        String body = send(post("/api/coach/teams"), "profe", Map.of("category", "INFANTIL:B", "branch", "FEMENINO",
                        "excluded", List.of(503)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Infantil B Femenino"))
                .andExpect(jsonPath("$.sidgadCategory").value("INFANTIL:B"))
                .andExpect(jsonPath("$.sidgadBranch").value("FEMENINO"))
                .andReturn().getResponse().getContentAsString();
        long infantil = json.readTree(body).get("id").asLong();
        // Para cargar acciones, solo las incluidas; el "7" es de Ana (incluida), no de Carla.
        mvc.perform(get("/api/coach/squad").param("team", String.valueOf(infantil)).with(como("profe")))
                .andExpect(jsonPath("$[*].name").value(contains("Bea Ruiz", "Ana Gómez")))
                .andExpect(jsonPath("$[0].goalkeeper").value(true))
                .andExpect(jsonPath("$[1].number").value("7"))
                .andExpect(jsonPath("$[1].id").value(501));
        // En la lista de jugadores, Carla figura con el equipo como pendiente.
        mvc.perform(get("/api/coach/players").with(como("profe")))
                .andExpect(jsonPath("$[?(@.id == 503)].teams[*]").isEmpty())
                .andExpect(jsonPath("$[?(@.id == 503)].pending[0]").value(contains((int) infantil)))
                .andExpect(jsonPath("$[?(@.id == 503)].number").value(contains("")));

        // Después se la suma.
        mvc.perform(post("/api/coach/players/503/teams/" + infantil).with(como("profe")).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.teams[0]").value((int) infantil))
                .andExpect(jsonPath("$.pending").isEmpty());
        mvc.perform(get("/api/coach/squad").param("team", String.valueOf(infantil)).with(como("profe")))
                .andExpect(jsonPath("$", hasSize(3)));
        // Destildarla la saca del equipo y la deja como "no incluida", para volver a sumarla.
        mvc.perform(delete("/api/coach/players/503/teams/" + infantil).with(como("profe")).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.teams").isEmpty())
                .andExpect(jsonPath("$.pending[0]").value((int) infantil));
        mvc.perform(get("/api/coach/squad").param("team", String.valueOf(infantil)).with(como("profe")))
                .andExpect(jsonPath("$", hasSize(2)));
        mvc.perform(post("/api/coach/players/503/teams/" + infantil).with(como("profe")).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.pending").isEmpty());

        // Otro equipo con una jugadora que ya tiene: no se duplica, suma el equipo.
        String other = send(post("/api/coach/teams"), "profe", Map.of("name", "Primera", "category", "PRIMERA",
                "branch", "MASCULINO")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long primeraSidgad = json.readTree(other).get("id").asLong();
        mvc.perform(get("/api/coach/players").with(como("profe")))
                .andExpect(jsonPath("$[?(@.id == 501)]", hasSize(1)))
                .andExpect(jsonPath("$[?(@.id == 501)].teams[*]").value(contains((int) infantil, (int) primeraSidgad)));
        // Una categoría que no es del club, no.
        send(post("/api/coach/teams"), "profe", Map.of("category", "JUVENIL", "branch", "MASCULINO"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void onlyTheCoachSeesTheirMatches() throws Exception {
        long id = create("profe", "HURACAN");
        mvc.perform(get("/api/coach/matches/" + id).with(como("profe"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.opponent").value("HURACAN"))
                .andExpect(jsonPath("$.team").value("Primera A"))
                .andExpect(jsonPath("$.date").value("2026-10-18"))
                .andExpect(jsonPath("$.home").value(true))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.clock.remaining").value(1500));
        mvc.perform(get("/api/coach/matches/" + id).with(como("otro"))).andExpect(status().isNotFound());
        // El mismo rival y la misma fecha retoman el partido en vez de crear otro.
        send(post("/api/coach/matches"), "profe", match("HURACAN")).andExpect(jsonPath("$.id").value(id));
        mvc.perform(get("/api/coach/matches").with(como("profe"))).andExpect(jsonPath("$", hasSize(1)));
        mvc.perform(get("/api/coach/matches").with(user("c").authorities(() -> "CLUBFPP"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/coach/matches").with(user("p").authorities(() -> "PLAYERFPP"))).andExpect(status().isForbidden());
        send(post("/api/coach/matches"), "profe", Map.of("date", "2026-10-18")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Escribí el rival."));
        // Los minutos de cada tiempo los elige el DT (sin elegir, 25'); sin fecha, hoy.
        send(post("/api/coach/matches"), "profe", Map.of("team", primera, "opponent", "SAN JUAN", "minutes", 20))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.periodMinutes").value(20))
                .andExpect(jsonPath("$.date").value("2026-10-16"));
        send(post("/api/coach/matches"), "profe", Map.of("opponent", "X", "minutes", 50)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Cada tiempo dura entre 10 y 40 minutos."));
        // Sin equipos no hay partidos.
        send(post("/api/coach/matches"), "otro", Map.of("opponent", "HURACAN")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Primero creá un equipo en la sección Equipos."));
    }

    @Test
    void clockAndMatchActions() throws Exception {
        long id = create("profe", "HURACAN");
        String base = "/api/coach/matches/" + id;
        send(post(base + "/clock"), "profe", Map.of("action", "PAUSE")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Primero iniciá el partido."));
        send(post(base + "/clock"), "profe", Map.of("action", "START")).andExpect(status().isOk())
                .andExpect(jsonPath("$.clock.running").value(true));
        clock.advance(390);
        mvc.perform(get(base).with(como("profe"))).andExpect(jsonPath("$.clock.remaining").value(1110));

        Map<String, Object> goal = action(forward, "SHOT", "GOAL", 1, 390);
        Map<String, Object> reinforcement = action(0, "CARD", "BLUE", 1, 500);
        reinforcement.put("playerName", "Refuerzo");
        List<Map<String, Object>> batch = List.of(goal, action(goalkeeper, "SAVE", null, 1, 400),
                action(goalkeeper, "GOAL_CONCEDED", null, 1, 420), reinforcement);
        send(post(base + "/actions"), "profe", batch).andExpect(status().isOk())
                .andExpect(jsonPath("$.goalsFor").value(1))
                .andExpect(jsonPath("$.goalsAgainst").value(1))
                .andExpect(jsonPath("$.actions", hasSize(4)))
                .andExpect(jsonPath("$.actions[0].playerName").value("Pedro Delantero"));
        // Reenviar la misma tanda (sin señal) no duplica.
        send(post(base + "/actions"), "profe", batch).andExpect(jsonPath("$.actions", hasSize(4)));

        send(post(base + "/actions"), "profe", List.of(action(forward, "TURNOVER", "GOAL", 1, 10)))
                .andExpect(status().isBadRequest());
        send(post(base + "/actions"), "profe", List.of(action(99, "FOUL", "COMMITTED", 1, 10)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("El jugador 99 no está en el plantel del equipo."));
        send(post(base + "/actions"), "profe", List.of(action(forward, "FOUL", "COMMITTED", 1, 1501)))
                .andExpect(status().isBadRequest());

        // Deshacer el gol.
        mvc.perform(delete(base + "/actions/" + goal.get("id")).with(como("profe")).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.goalsFor").value(0))
                .andExpect(jsonPath("$.actions", hasSize(3)));

        send(post(base + "/clock"), "profe", Map.of("action", "END_PERIOD"))
                .andExpect(jsonPath("$.status").value("HALF_TIME"));
        send(post(base + "/clock"), "profe", Map.of("action", "START"))
                .andExpect(jsonPath("$.clock.period").value(2))
                .andExpect(jsonPath("$.clock.remaining").value(1500));
        send(post(base + "/close"), "profe", Map.of()).andExpect(jsonPath("$.status").value("CLOSED"));
        send(post(base + "/actions"), "profe", List.of(action(forward, "FOUL", "RECEIVED", 2, 10)))
                .andExpect(status().isConflict());
        send(post(base + "/reopen"), "profe", Map.of()).andExpect(jsonPath("$.status").value("FINISHED"));
    }

    @Test
    void deletingAMatchDeletesItsActions() throws Exception {
        long one = create("profe", "HURACAN");
        long two = create("profe", "GEBA");
        send(post("/api/coach/matches/" + one + "/actions"), "profe", List.of(action(forward, "SHOT", "GOAL", 1, 30)))
                .andExpect(status().isOk());
        send(post("/api/coach/matches/" + two + "/actions"), "profe", List.of(action(forward, "SHOT", "GOAL", 1, 30),
                action(forward, "SHOT", "NO_GOAL", 1, 40))).andExpect(status().isOk());
        mvc.perform(delete("/api/coach/matches/" + two).with(como("otro")).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(delete("/api/coach/matches/" + two).with(como("profe")).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get("/api/coach/matches/" + two).with(como("profe"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/coach/matches").with(como("profe"))).andExpect(jsonPath("$", hasSize(1)));
        mvc.perform(get("/api/coach/actions").with(como("profe"))).andExpect(jsonPath("$", hasSize(1)));
        assertThat(actions.findAll()).hasSize(1);

        // Borrar al DT borra en cascada sus equipos, sus partidos (con sus acciones) y sus jugadores.
        users.delete(users.findByUsername("profe").orElseThrow());
        assertThat(matches.findAll()).isEmpty();
        assertThat(actions.findAll()).isEmpty();
    }

    @Test
    void quotaOf25MatchesPerMonth() throws Exception {
        for (int i = 0; i < 25; i++) {
            send(post("/api/coach/matches"), "profe", Map.of("opponent", "Rival " + i, "home", false))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.home").value(false))
                    .andExpect(jsonPath("$.periodMinutes").value(25));
        }
        send(post("/api/coach/matches"), "profe", Map.of("opponent", "Uno más")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Ya cargaste los 25 partidos de este mes. El cupo se renueva el 15/11."));
        mvc.perform(get("/api/coach/matches").with(como("profe"))).andExpect(jsonPath("$", hasSize(25)));
        // Eliminar un partido no devuelve el lugar en el cupo.
        long one = matches.findAll().getFirst().getId();
        mvc.perform(delete("/api/coach/matches/" + one).with(como("profe")).with(csrf())).andExpect(status().isNoContent());
        send(post("/api/coach/matches"), "profe", Map.of("opponent", "Otra vez")).andExpect(status().isConflict());
        // El mes siguiente de la suscripción vuelve a tener cupo.
        clock.now = Instant.parse("2026-11-15T12:00:00Z");
        send(post("/api/coach/matches"), "profe", Map.of("opponent", "Nuevo mes")).andExpect(status().isCreated());
    }

    @Test
    void theFreePlanIsThreeTeamsAndFiveMatchesPerMonthUntilEndOf2026() throws Exception {
        coach("gratis", "COACH_FREE");
        team("gratis", "Mi equipo");
        team("gratis", "Segundo");
        team("gratis", "Tercero");
        send(post("/api/coach/teams"), "gratis", Map.of("name", "Otro")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Tu plan DT free permite hasta 3 equipos. Para sumar otro, borrá "
                        + "uno sin partidos o pasate al plan mensual."));
        for (int i = 0; i < 5; i++) {
            send(post("/api/coach/matches"), "gratis", Map.of("opponent", "Rival " + i)).andExpect(status().isCreated());
        }
        send(post("/api/coach/matches"), "gratis", Map.of("opponent", "Uno más")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Ya cargaste los 5 partidos de este mes. El cupo se renueva el 15/11."));
        // El 31/12/2026 (otro mes de la suscripción) todavía vale; el 1/1/2027 ya no se pueden empezar partidos.
        clock.now = Instant.parse("2026-12-31T20:00:00Z");
        send(post("/api/coach/matches"), "gratis", Map.of("opponent", "Fin de año")).andExpect(status().isCreated());
        clock.now = Instant.parse("2027-01-01T13:00:00Z");
        send(post("/api/coach/matches"), "gratis", Map.of("opponent", "Año nuevo")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Tu plan DT free terminó el 31/12/2026. Pasate al plan mensual "
                        + "para seguir cargando partidos."));
    }

    @Test
    void actionsOfAllTeamMatches() throws Exception {
        long one = create("profe", "HURACAN");
        long two = create("profe", "GEBA A");
        send(post("/api/coach/matches/" + one + "/actions"), "profe", List.of(action(forward, "SHOT", "GOAL", 1, 30),
                action(goalkeeper, "SAVE", null, 1, 40))).andExpect(status().isOk());
        Map<String, Object> deletedAt = action(forward, "FOUL", "COMMITTED", 1, 50);
        send(post("/api/coach/matches/" + two + "/actions"), "profe", List.of(action(forward, "TURNOVER", "RIVAL_HALF", 1, 10),
                deletedAt)).andExpect(status().isOk());
        mvc.perform(delete("/api/coach/matches/" + two + "/actions/" + deletedAt.get("id")).with(como("profe")).with(csrf()))
                .andExpect(status().isOk());
        // Un partido de otro equipo del DT no entra.
        long infantil = team("profe", "Infantil");
        send(post("/api/coach/matches"), "profe", Map.of("team", infantil, "opponent", "SAN JUAN"))
                .andExpect(status().isCreated());

        mvc.perform(get("/api/coach/actions").param("team", String.valueOf(primera)).with(como("profe")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))   // la borrada no cuenta
                .andExpect(jsonPath("$[0].matchId").value(one))
                .andExpect(jsonPath("$[0].playerName").value("Pedro Delantero"))
                .andExpect(jsonPath("$[2].kind").value("TURNOVER"));
        mvc.perform(get("/api/coach/actions").param("team", String.valueOf(infantil)).with(como("profe")))
                .andExpect(jsonPath("$", hasSize(0)));
        // Otro DT no ve las acciones de este.
        mvc.perform(get("/api/coach/actions").param("team", String.valueOf(primera)).with(como("otro")))
                .andExpect(status().isNotFound());
    }

    @Test
    void matchReportAndPeriodReport() throws Exception {
        String body = send(post("/api/coach/matches"), "profe", Map.of("team", primera, "opponent", "HURACAN",
                "date", "2026-10-18", "tournament", "  Apertura   2026 ")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.tournament").value("Apertura 2026"))
                .andReturn().getResponse().getContentAsString();
        long id = json.readTree(body).get("id").asLong();
        String base = "/api/coach/matches/" + id;
        Map<String, Object> reinforcement = action(0, "FOUL", "COMMITTED", 2, 100);
        reinforcement.put("playerName", "Refuerzo");
        send(post(base + "/actions"), "profe", List.of(action(forward, "SHOT", "GOAL", 1, 30),
                action(forward, "SHOT", "NO_GOAL", 1, 60), action(forward, "PENALTY_FOR", "GOAL", 1, 90),
                action(forward, "FREE_HIT_FOR", "NO_GOAL", 1, 120), action(goalkeeper, "SAVE", null, 1, 150),
                action(goalkeeper, "PENALTY_AGAINST", "SAVED", 1, 180), action(goalkeeper, "GOAL_CONCEDED", null, 1, 210),
                action(forward, "TURNOVER", "OWN_HALF", 1, 240), action(forward, "RECOVERY", "RIVAL_HALF", 1, 270),
                action(forward, "FOUL", "RECEIVED", 2, 10), action(forward, "CARD", "BLUE", 2, 50), reinforcement))
                .andExpect(status().isOk());

        mvc.perform(get(base + "/report").with(como("profe"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("VELEZ 2 - 1 HURACAN"))
                .andExpect(jsonPath("$.detail").value("Primera A · Local · Apertura 2026 · 18/10/2026"))
                .andExpect(jsonPath("$.totals.goals").value(2))
                .andExpect(jsonPath("$.totals.goalsConceded").value(1))
                .andExpect(jsonPath("$.totals.shots").value(2))
                .andExpect(jsonPath("$.totals.shotEffectiveness").value(50))
                .andExpect(jsonPath("$.totals.saves").value(2))
                .andExpect(jsonPath("$.totals.turnovers").value(1))
                .andExpect(jsonPath("$.totals.recoveries").value(1))
                .andExpect(jsonPath("$.totals.foulsReceived").value(1))
                .andExpect(jsonPath("$.totals.foulsCommitted").value(1))
                .andExpect(jsonPath("$.totals.blues").value(1))
                .andExpect(jsonPath("$.totals.penaltyEffectiveness").value(100))
                .andExpect(jsonPath("$.totals.freeHitEffectiveness").value(0))
                .andExpect(jsonPath("$.players[*].name").value(contains("Juan Arquero", "Pedro Delantero", "Refuerzo")))
                .andExpect(jsonPath("$.players[0].goalkeeper").value(true))
                .andExpect(jsonPath("$.events", hasSize(12)))
                .andExpect(jsonPath("$.events[0].minute").value("1T 00:30"))
                .andExpect(jsonPath("$.events[0].action").value("Tiro al arco: Gol"))
                .andExpect(jsonPath("$.events[11].player").value("Refuerzo"));
        // Solo un jugador: sus métricas y sus eventos.
        mvc.perform(get(base + "/report").param("player", "j" + forward).with(como("profe")))
                .andExpect(jsonPath("$.player.name").value("Pedro Delantero"))
                .andExpect(jsonPath("$.totals.goals").value(2))
                .andExpect(jsonPath("$.totals.goalsConceded").value(0))
                .andExpect(jsonPath("$.players", hasSize(1)))
                .andExpect(jsonPath("$.events", hasSize(8)));
        mvc.perform(get(base + "/report").param("player", "j99").with(como("profe")))
                .andExpect(status().isBadRequest());
        mvc.perform(get(base + "/report").with(como("otro"))).andExpect(status().isNotFound());
        byte[] pdf = mvc.perform(get(base + "/report.pdf").with(como("profe"))).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("Content-Disposition", containsString("informe-huracan-2026-10-18.pdf")))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(pdf, 0, 4)).isEqualTo("%PDF");

        // Los informes por período solo cuentan los partidos finalizados.
        String eq = String.valueOf(primera);
        mvc.perform(get("/api/coach/reports").param("team", eq).param("period", "MONTH").param("value", "2026-10")
                        .with(como("profe")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("No hay partidos finalizados en Octubre 2026."));
        for (String a : List.of("START", "END_PERIOD", "START", "END_PERIOD")) {
            send(post(base + "/clock"), "profe", Map.of("action", a)).andExpect(status().isOk());
        }
        mvc.perform(get("/api/coach/reports").param("team", eq).param("period", "MONTH").param("value", "2026-10")
                        .with(como("profe")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Octubre 2026"))
                .andExpect(jsonPath("$.detail").value("Primera A · 1 partido"))
                .andExpect(jsonPath("$.balance.won").value(1))
                .andExpect(jsonPath("$.matches[0].outcome").value("G"))
                .andExpect(jsonPath("$.events", hasSize(0)));
        mvc.perform(get("/api/coach/reports").param("period", "TWO_MONTHS").param("value", "2026-5").with(como("profe")))
                .andExpect(jsonPath("$.title").value("Bimestre septiembre-octubre 2026"));
        mvc.perform(get("/api/coach/reports").param("period", "SEMESTER").param("value", "2026-2").with(como("profe")))
                .andExpect(jsonPath("$.title").value("2.º semestre 2026 (julio a diciembre)"));
        mvc.perform(get("/api/coach/reports").param("period", "CHAMPIONSHIP").param("value", "Apertura 2026")
                        .with(como("profe")))
                .andExpect(jsonPath("$.title").value("Campeonato Apertura 2026"))
                .andExpect(jsonPath("$.totals.goals").value(2));
        mvc.perform(get("/api/coach/reports").param("period", "CHAMPIONSHIP").param("value", "").with(como("profe")))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/coach/reports").param("period", "ANIO").param("value", "2026").with(como("profe")))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/coach/reports").param("period", "MONTH").param("value", "2026-13").with(como("profe")))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/coach/reports.pdf").param("period", "MONTH").param("value", "2026-10")
                        .param("player", "j" + goalkeeper).with(como("profe")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("informe-octubre-2026-juan-arquero.pdf")));
        // Otro DT no tiene equipos.
        mvc.perform(get("/api/coach/reports").param("period", "MONTH").param("value", "2026-10").with(como("otro")))
                .andExpect(status().isBadRequest());
    }
}
