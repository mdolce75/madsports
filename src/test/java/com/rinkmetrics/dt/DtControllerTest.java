package com.rinkmetrics.dt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rinkmetrics.account.Perfil;
import com.rinkmetrics.account.SuscripcionTypeRepository;
import com.rinkmetrics.account.Usuario;
import com.rinkmetrics.account.UsuarioRepository;
import com.rinkmetrics.catalog.CatalogService;
import com.rinkmetrics.catalog.CatalogService.ClubView;
import com.rinkmetrics.catalog.CatalogService.LevelView;
import com.rinkmetrics.player.RosterService;
import com.rinkmetrics.player.RosterService.PlantelJugador;
import com.rinkmetrics.player.RosterService.RosterPlayer;
import com.rinkmetrics.sidgad.model.Federacion;
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

/** API de Métricas DT, con la base H2 en memoria y un reloj que se adelanta a mano. Nada sale de SIDGAD. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:planilla;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class DtControllerTest {

    static final ZoneId BA = ZoneId.of("America/Argentina/Buenos_Aires");
    static final Instant INICIO = Instant.parse("2026-10-16T18:00:00Z");

    /** Reloj de prueba: arranca el 16/10/2026 a las 15:00 (Buenos Aires) y avanza con {@link #avanzar}. */
    static class RelojDePrueba extends Clock {
        Instant ahora = INICIO;

        void avanzar(int segundos) {
            ahora = ahora.plusSeconds(segundos);
        }

        @Override public ZoneId getZone() { return BA; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return ahora; }
    }

    @TestConfiguration
    static class Config {
        @Bean @Primary
        RelojDePrueba relojDePrueba() {
            return new RelojDePrueba();
        }
    }

    static Map<String, Object> accion(int jugadorId, String tipo, String resultado, int tiempo, int segundo) {
        Map<String, Object> m = new HashMap<>(Map.of("id", UUID.randomUUID().toString(),
                "jugadorId", jugadorId, "tipo", tipo, "tiempo", tiempo, "segundo", segundo));
        if (resultado != null) {
            m.put("resultado", resultado);
        }
        return m;
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UsuarioRepository usuarios;
    @Autowired SuscripcionTypeRepository suscripciones;
    @Autowired PartidoDtRepository partidos;
    @Autowired AccionDtRepository acciones;
    @Autowired RelojDePrueba reloj;
    @Autowired PlantelDtService planteles;

    /** El plantel ya no sale de SIDGAD: si alguien lo pidiera, el test lo detecta. */
    @MockitoBean RosterService roster;
    /** El club del DT en SIDGAD (sus categorías): solo lo piden los equipos armados desde SIDGAD. */
    @MockitoBean CatalogService catalog;

    /** Equipo "Primera A" de profe y sus dos jugadores. */
    long primera;
    int arquero;
    int delantero;

    @BeforeEach
    void setUp() throws Exception {
        acciones.deleteAll();
        partidos.deleteAll();
        usuarios.deleteAll();
        reloj.ahora = INICIO;
        dt("profe");
        dt("otro");
        primera = equipo("profe", "Primera A");
        arquero = jugador("profe", "Juan", "Arquero", "1", true, primera);
        delantero = jugador("profe", "Pedro", "Delantero", "7", false, primera);
    }

    void dt(String nombre) {
        dt(nombre, "DT_MENSUAL");
    }

    void dt(String nombre, String plan) {
        Usuario u = new Usuario(nombre, "x", nombre + "@example.com", "Ana", "Pérez", 74, "VELEZ", "CAVS",
                EnumSet.of(Perfil.DTFPP), Instant.now());
        u.suscribir(suscripciones.findByCodigo(plan).orElseThrow(), LocalDate.of(2026, 9, 15));
        usuarios.save(u);
    }

    static RequestPostProcessor como(String usuario) {
        return user(usuario).authorities(() -> "DTFPP");
    }

    ResultActions enviar(MockHttpServletRequestBuilder req, String usuario, Object body) throws Exception {
        return mvc.perform(req.with(como(usuario)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    long equipo(String usuario, String nombre) throws Exception {
        String body = enviar(post("/api/dt/equipos"), usuario, Map.of("nombre", nombre))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("id").asLong();
    }

    int jugador(String usuario, String nombre, String apellido, String numero, boolean esArquero, Long... equipos)
            throws Exception {
        String body = enviar(post("/api/dt/jugadores"), usuario, Map.of("nombre", nombre, "apellido", apellido,
                "numero", numero, "arquero", esArquero, "equipos", List.of(equipos)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("id").asInt();
    }

    Map<String, Object> partido(String rival) {
        return Map.of("equipo", primera, "rival", rival, "fecha", "2026-10-18", "local", true);
    }

    long crear(String usuario, String rival) throws Exception {
        String body = enviar(post("/api/dt/partidos"), usuario, partido(rival))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("id").asLong();
    }

    @Test
    void abmDeEquiposConSuBalance() throws Exception {
        long infantil = equipo("profe", "Infantil");
        enviar(post("/api/dt/equipos"), "profe", Map.of("nombre", " primera   a ")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Ya tenés un equipo llamado primera a."));
        enviar(post("/api/dt/equipos"), "profe", Map.of("nombre", " ")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Escribí el nombre del equipo."));
        // El plan mensual permite hasta 3 equipos.
        equipo("profe", "Juveniles");
        enviar(post("/api/dt/equipos"), "profe", Map.of("nombre", "Cuarto")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Tu plan DT mensual permite hasta 3 equipos. Para sumar otro, borrá "
                        + "uno sin partidos."));
        enviar(put("/api/dt/equipos/" + infantil), "profe", Map.of("nombre", "Infantil B")).andExpect(status().isOk())
                .andExpect(jsonPath("$.nombre").value("Infantil B"));

        // Balance: un partido ganado y finalizado; otro sin terminar no cuenta.
        long id = crear("profe", "HURACAN");
        enviar(post("/api/dt/partidos/" + id + "/acciones"), "profe", List.of(accion(delantero, "TIRO", "GOL", 1, 30)))
                .andExpect(status().isOk());
        for (String a : List.of("INICIAR", "FIN_TIEMPO", "INICIAR", "FIN_TIEMPO")) {
            enviar(post("/api/dt/partidos/" + id + "/reloj"), "profe", Map.of("accion", a)).andExpect(status().isOk());
        }
        crear("profe", "GEBA");
        mvc.perform(get("/api/dt/equipos").with(como("profe"))).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].nombre").value(contains("Primera A", "Infantil B", "Juveniles")))
                .andExpect(jsonPath("$[0].jugados").value(1))
                .andExpect(jsonPath("$[0].ganados").value(1))
                .andExpect(jsonPath("$[0].empatados").value(0))
                .andExpect(jsonPath("$[0].perdidos").value(0));

        // Un equipo con partidos no se borra; uno sin partidos, sí.
        mvc.perform(delete("/api/dt/equipos/" + primera).with(como("profe")).with(csrf())).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Primera A tiene 2 partidos cargados: no se puede borrar."));
        mvc.perform(delete("/api/dt/equipos/" + infantil).with(como("profe")).with(csrf())).andExpect(status().isNoContent());
        // Los equipos de un DT no son de otro.
        mvc.perform(get("/api/dt/equipos").with(como("otro"))).andExpect(jsonPath("$", hasSize(0)));
        enviar(put("/api/dt/equipos/" + primera), "otro", Map.of("nombre", "Mío")).andExpect(status().isNotFound());
    }

    @Test
    void altasSimultaneasDelMismoDtNoChocan() throws Exception {
        // Las altas toman el id siguiente al último del DT: las que chocan se reintentan, ninguna termina en error.
        Usuario profe = usuarios.findByUsuario("profe").orElseThrow();
        int n = 6;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch largada = new CountDownLatch(1);
        List<Future<PlantelDtService.JugadorPlantel>> altas = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            var datos = new PlantelDtService.DatosJugador("Suplente" + i, "Ríos", null, false, List.of(primera));
            altas.add(pool.submit(() -> {
                largada.await();
                return planteles.alta(profe, datos);
            }));
        }
        largada.countDown();
        Set<Integer> ids = new HashSet<>();
        for (var alta : altas) {
            ids.add(alta.get(30, TimeUnit.SECONDS).id());
        }
        pool.shutdown();
        assertThat(ids).hasSize(n).doesNotContain(arquero, delantero);
        assertThat(planteles.plantel(profe, primera)).hasSize(n + 2);
    }

    @Test
    void abmDeJugadoresEnUnoOVariosEquipos() throws Exception {
        long infantil = equipo("profe", "Infantil");
        enviar(post("/api/dt/jugadores"), "profe", Map.of("nombre", "Sin", "apellido", "Equipo"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Asigná el jugador a un equipo."));
        // Un jugador en los dos equipos.
        int doble = jugador("profe", "Tomás", "Doble", "09", false, primera, infantil);
        assertThat(doble).isGreaterThanOrEqualTo(900_000_000);
        // El número no se repite dentro de un equipo, pero sí entre equipos distintos.
        enviar(post("/api/dt/jugadores"), "profe", Map.of("nombre", "Otro", "apellido", "Siete", "numero", "7",
                "equipos", List.of(primera))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("El número 7 ya es de Pedro Delantero en ese equipo."));
        jugador("profe", "Lucas", "Siete", "7", false, infantil);
        enviar(post("/api/dt/jugadores"), "profe", Map.of("nombre", "A", "apellido", "B", "numero", "1234",
                "equipos", List.of(primera))).andExpect(status().isBadRequest());

        mvc.perform(get("/api/dt/plantel").param("equipo", String.valueOf(primera)).with(como("profe")))
                .andExpect(jsonPath("$[*].nombre").value(contains("Juan Arquero", "Pedro Delantero", "Tomás Doble")))
                .andExpect(jsonPath("$[2].numero").value("9"));
        mvc.perform(get("/api/dt/plantel").param("equipo", String.valueOf(infantil)).with(como("profe")))
                .andExpect(jsonPath("$[*].nombre").value(contains("Lucas Siete", "Tomás Doble")));
        mvc.perform(get("/api/dt/jugadores").with(como("profe"))).andExpect(jsonPath("$", hasSize(4)))
                .andExpect(jsonPath("$[0].nombre").value("Juan Arquero"))
                .andExpect(jsonPath("$[2].nombre").value("Tomás Doble"))
                .andExpect(jsonPath("$[2].equipos", hasSize(2)));

        // Lo saca de Infantil y le corrige el nombre: el informe del partido muestra el nombre nuevo.
        long id = crear("profe", "HURACAN");
        enviar(post("/api/dt/partidos/" + id + "/acciones"), "profe", List.of(accion(doble, "TIRO", "GOL", 1, 30)))
                .andExpect(status().isOk());
        enviar(put("/api/dt/jugadores/" + doble), "profe", Map.of("nombre", "Tomás José", "apellido", "Doble",
                "numero", "10", "equipos", List.of(primera))).andExpect(status().isOk())
                .andExpect(jsonPath("$.nombre").value("Tomás José Doble"))
                .andExpect(jsonPath("$.equipos", hasSize(1)));
        mvc.perform(get("/api/dt/plantel").param("equipo", String.valueOf(infantil)).with(como("profe")))
                .andExpect(jsonPath("$[*].nombre").value(contains("Lucas Siete")));
        mvc.perform(get("/api/dt/partidos/" + id + "/informe").with(como("profe")))
                .andExpect(jsonPath("$.jugadores[*].nombre").value(contains("Tomás José Doble")));

        // Baja: ya no se le cargan acciones; las suyas quedan en el informe.
        mvc.perform(delete("/api/dt/jugadores/" + doble).with(como("profe")).with(csrf())).andExpect(status().isNoContent());
        enviar(post("/api/dt/partidos/" + id + "/acciones"), "profe", List.of(accion(doble, "FALTA", "COMETIDA", 1, 50)))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/dt/partidos/" + id + "/informe").with(como("profe")))
                .andExpect(jsonPath("$.totales.goles").value(1));
        mvc.perform(delete("/api/dt/jugadores/" + doble).with(como("profe")).with(csrf())).andExpect(status().isNotFound());
        // Un jugador no se puede poner en el equipo de otro DT.
        long ajeno = equipo("otro", "Ajeno");
        enviar(post("/api/dt/jugadores"), "profe", Map.of("nombre", "X", "apellido", "Y", "equipos", List.of(ajeno)))
                .andExpect(status().isNotFound());
        enviar(put("/api/dt/jugadores/" + delantero), "otro", Map.of("nombre", "X", "apellido", "Y",
                "equipos", List.of(ajeno))).andExpect(status().isNotFound());
        verifyNoInteractions(roster);
    }

    /** Vélez en SIDGAD: Primera Masculino e Infantil B en las dos ramas; el plantel de Infantil B Femenino y Primera. */
    void clubEnSidgad() {
        when(catalog.defaultYear()).thenReturn(2026);
        when(catalog.club(Federacion.FPP, 74, 2026)).thenReturn(new ClubView(74, "CAVS", "VELEZ", List.of(
                new LevelView("PRIMERA", "Primera", List.of("MASCULINO")),
                new LevelView("INFANTIL:B", "Infantil B", List.of("MASCULINO", "FEMENINO")))));
        when(roster.players(Federacion.FPP, 74, 2026, "INFANTIL:B", "FEMENINO")).thenReturn(List.of(
                new RosterPlayer(501, "Ana Gómez", "Ana", "Gómez", "Infantil B Femenino"),
                new RosterPlayer(502, "Bea Ruiz", "Bea", "Ruiz", "Infantil B Femenino"),
                new RosterPlayer(503, "Carla Díaz", "Carla", "Díaz", "Infantil B Femenino")));
        when(roster.plantel(Federacion.FPP, 74, 2026, "INFANTIL:B", "FEMENINO")).thenReturn(List.of(
                new PlantelJugador(501, "07", "Ana Gómez", false), new PlantelJugador(502, "1", "Bea Ruiz", true),
                new PlantelJugador(503, "07", "Carla Díaz", false)));
        when(roster.players(Federacion.FPP, 74, 2026, "PRIMERA", "MASCULINO")).thenReturn(List.of(
                new RosterPlayer(501, "Ana Gómez", "Ana", "Gómez", "Primera Masculino"),
                new RosterPlayer(504, "Dante Paz", "Dante", "Paz", "Primera Masculino")));
        when(roster.plantel(Federacion.FPP, 74, 2026, "PRIMERA", "MASCULINO")).thenReturn(List.of(
                new PlantelJugador(504, "10", "Dante Paz", false)));
    }

    @Test
    void equipoDesdeUnaCategoriaDelClubConSuPlantelDeSidgad() throws Exception {
        clubEnSidgad();
        mvc.perform(get("/api/dt/club/categorias").with(como("profe"))).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].nombre").value(contains("Primera Masculino", "Infantil B Masculino",
                        "Infantil B Femenino")))
                .andExpect(jsonPath("$[1].clave").value("INFANTIL:B"))
                .andExpect(jsonPath("$[1].rama").value("MASCULINO"));
        // Su plantel en SIDGAD, por apellido, con el número más usado ("07" -> "7") y los arqueros.
        mvc.perform(get("/api/dt/club/plantel").param("categoria", "INFANTIL:B").param("rama", "FEMENINO")
                        .with(como("profe"))).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].apellido").value(contains("Díaz", "Gómez", "Ruiz")))
                .andExpect(jsonPath("$[1].numero").value("7"))
                .andExpect(jsonPath("$[2].arquero").value(true));
        mvc.perform(get("/api/dt/club/plantel").param("categoria", "JUVENIL").param("rama", "MASCULINO")
                .with(como("profe"))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Esa categoría no es de tu club."));

        // El equipo entra con todo el plantel menos Carla, que queda "no incluida". Sin nombre: el de la categoría.
        String body = enviar(post("/api/dt/equipos"), "profe", Map.of("categoria", "INFANTIL:B", "rama", "FEMENINO",
                        "excluidos", List.of(503)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.nombre").value("Infantil B Femenino"))
                .andExpect(jsonPath("$.categoriaSidgad").value("INFANTIL:B"))
                .andExpect(jsonPath("$.ramaSidgad").value("FEMENINO"))
                .andReturn().getResponse().getContentAsString();
        long infantil = json.readTree(body).get("id").asLong();
        // Para cargar acciones, solo las incluidas; el "7" es de Ana (incluida), no de Carla.
        mvc.perform(get("/api/dt/plantel").param("equipo", String.valueOf(infantil)).with(como("profe")))
                .andExpect(jsonPath("$[*].nombre").value(contains("Bea Ruiz", "Ana Gómez")))
                .andExpect(jsonPath("$[0].arquero").value(true))
                .andExpect(jsonPath("$[1].numero").value("7"))
                .andExpect(jsonPath("$[1].id").value(501));
        // En la lista de jugadores, Carla figura con el equipo como pendiente.
        mvc.perform(get("/api/dt/jugadores").with(como("profe")))
                .andExpect(jsonPath("$[?(@.id == 503)].equipos[*]").isEmpty())
                .andExpect(jsonPath("$[?(@.id == 503)].pendientes[0]").value(contains((int) infantil)))
                .andExpect(jsonPath("$[?(@.id == 503)].numero").value(contains("")));

        // Después se la suma.
        mvc.perform(post("/api/dt/jugadores/503/equipos/" + infantil).with(como("profe")).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.equipos[0]").value((int) infantil))
                .andExpect(jsonPath("$.pendientes").isEmpty());
        mvc.perform(get("/api/dt/plantel").param("equipo", String.valueOf(infantil)).with(como("profe")))
                .andExpect(jsonPath("$", hasSize(3)));

        // Otro equipo con una jugadora que ya tiene: no se duplica, suma el equipo.
        String otro = enviar(post("/api/dt/equipos"), "profe", Map.of("nombre", "Primera", "categoria", "PRIMERA",
                "rama", "MASCULINO")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long primeraSidgad = json.readTree(otro).get("id").asLong();
        mvc.perform(get("/api/dt/jugadores").with(como("profe")))
                .andExpect(jsonPath("$[?(@.id == 501)]", hasSize(1)))
                .andExpect(jsonPath("$[?(@.id == 501)].equipos[*]").value(contains((int) infantil, (int) primeraSidgad)));
        // Una categoría que no es del club, no.
        enviar(post("/api/dt/equipos"), "profe", Map.of("categoria", "JUVENIL", "rama", "MASCULINO"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void soloElDtVeSusPartidos() throws Exception {
        long id = crear("profe", "HURACAN");
        mvc.perform(get("/api/dt/partidos/" + id).with(como("profe"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.rival").value("HURACAN"))
                .andExpect(jsonPath("$.equipo").value("Primera A"))
                .andExpect(jsonPath("$.fecha").value("2026-10-18"))
                .andExpect(jsonPath("$.local").value(true))
                .andExpect(jsonPath("$.estado").value("PENDIENTE"))
                .andExpect(jsonPath("$.reloj.restante").value(1500));
        mvc.perform(get("/api/dt/partidos/" + id).with(como("otro"))).andExpect(status().isNotFound());
        // El mismo rival y la misma fecha retoman el partido en vez de crear otro.
        enviar(post("/api/dt/partidos"), "profe", partido("HURACAN")).andExpect(jsonPath("$.id").value(id));
        mvc.perform(get("/api/dt/partidos").with(como("profe"))).andExpect(jsonPath("$", hasSize(1)));
        mvc.perform(get("/api/dt/partidos").with(user("c").authorities(() -> "CLUBFPP"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/dt/partidos").with(user("p").authorities(() -> "PLAYERFPP"))).andExpect(status().isForbidden());
        enviar(post("/api/dt/partidos"), "profe", Map.of("fecha", "2026-10-18")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Escribí el rival."));
        // Los minutos de cada tiempo los elige el DT (sin elegir, 25'); sin fecha, hoy.
        enviar(post("/api/dt/partidos"), "profe", Map.of("equipo", primera, "rival", "SAN JUAN", "minutos", 20))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.minutosTiempo").value(20))
                .andExpect(jsonPath("$.fecha").value("2026-10-16"));
        enviar(post("/api/dt/partidos"), "profe", Map.of("rival", "X", "minutos", 50)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Cada tiempo dura entre 10 y 40 minutos."));
        // Sin equipos no hay partidos.
        enviar(post("/api/dt/partidos"), "otro", Map.of("rival", "HURACAN")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Primero creá un equipo en la sección Equipo."));
    }

    @Test
    void relojYAccionesDelPartido() throws Exception {
        long id = crear("profe", "HURACAN");
        String base = "/api/dt/partidos/" + id;
        enviar(post(base + "/reloj"), "profe", Map.of("accion", "PAUSAR")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Primero iniciá el partido."));
        enviar(post(base + "/reloj"), "profe", Map.of("accion", "INICIAR")).andExpect(status().isOk())
                .andExpect(jsonPath("$.reloj.corriendo").value(true));
        reloj.avanzar(390);
        mvc.perform(get(base).with(como("profe"))).andExpect(jsonPath("$.reloj.restante").value(1110));

        Map<String, Object> gol = accion(delantero, "TIRO", "GOL", 1, 390);
        Map<String, Object> refuerzo = accion(0, "TARJETA", "AZUL", 1, 500);
        refuerzo.put("jugadorNombre", "Refuerzo");
        List<Map<String, Object>> tanda = List.of(gol, accion(arquero, "ATAJADA", null, 1, 400),
                accion(arquero, "GOL_RECIBIDO", null, 1, 420), refuerzo);
        enviar(post(base + "/acciones"), "profe", tanda).andExpect(status().isOk())
                .andExpect(jsonPath("$.golesFavor").value(1))
                .andExpect(jsonPath("$.golesContra").value(1))
                .andExpect(jsonPath("$.acciones", hasSize(4)))
                .andExpect(jsonPath("$.acciones[0].jugadorNombre").value("Pedro Delantero"));
        // Reenviar la misma tanda (sin señal) no duplica.
        enviar(post(base + "/acciones"), "profe", tanda).andExpect(jsonPath("$.acciones", hasSize(4)));

        enviar(post(base + "/acciones"), "profe", List.of(accion(delantero, "PERDIDA", "GOL", 1, 10)))
                .andExpect(status().isBadRequest());
        enviar(post(base + "/acciones"), "profe", List.of(accion(99, "FALTA", "COMETIDA", 1, 10)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("El jugador 99 no está en el plantel del equipo."));
        enviar(post(base + "/acciones"), "profe", List.of(accion(delantero, "FALTA", "COMETIDA", 1, 1501)))
                .andExpect(status().isBadRequest());

        // Deshacer el gol.
        mvc.perform(delete(base + "/acciones/" + gol.get("id")).with(como("profe")).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.golesFavor").value(0))
                .andExpect(jsonPath("$.acciones", hasSize(3)));

        enviar(post(base + "/reloj"), "profe", Map.of("accion", "FIN_TIEMPO"))
                .andExpect(jsonPath("$.estado").value("ENTRETIEMPO"));
        enviar(post(base + "/reloj"), "profe", Map.of("accion", "INICIAR"))
                .andExpect(jsonPath("$.reloj.tiempo").value(2))
                .andExpect(jsonPath("$.reloj.restante").value(1500));
        enviar(post(base + "/cerrar"), "profe", Map.of()).andExpect(jsonPath("$.estado").value("CERRADO"));
        enviar(post(base + "/acciones"), "profe", List.of(accion(delantero, "FALTA", "RECIBIDA", 2, 10)))
                .andExpect(status().isConflict());
        enviar(post(base + "/reabrir"), "profe", Map.of()).andExpect(jsonPath("$.estado").value("TERMINADO"));
    }

    @Test
    void eliminarUnPartidoBorraSusAcciones() throws Exception {
        long uno = crear("profe", "HURACAN");
        long dos = crear("profe", "GEBA");
        enviar(post("/api/dt/partidos/" + uno + "/acciones"), "profe", List.of(accion(delantero, "TIRO", "GOL", 1, 30)))
                .andExpect(status().isOk());
        enviar(post("/api/dt/partidos/" + dos + "/acciones"), "profe", List.of(accion(delantero, "TIRO", "GOL", 1, 30),
                accion(delantero, "TIRO", "NO_GOL", 1, 40))).andExpect(status().isOk());
        mvc.perform(delete("/api/dt/partidos/" + dos).with(como("otro")).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(delete("/api/dt/partidos/" + dos).with(como("profe")).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get("/api/dt/partidos/" + dos).with(como("profe"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/dt/partidos").with(como("profe"))).andExpect(jsonPath("$", hasSize(1)));
        mvc.perform(get("/api/dt/acciones").with(como("profe"))).andExpect(jsonPath("$", hasSize(1)));
        assertThat(acciones.findAll()).hasSize(1);

        // Borrar al DT borra en cascada sus equipos, sus partidos (con sus acciones) y sus jugadores.
        usuarios.delete(usuarios.findByUsuario("profe").orElseThrow());
        assertThat(partidos.findAll()).isEmpty();
        assertThat(acciones.findAll()).isEmpty();
    }

    @Test
    void cupoDe25PartidosPorMes() throws Exception {
        for (int i = 0; i < 25; i++) {
            enviar(post("/api/dt/partidos"), "profe", Map.of("rival", "Rival " + i, "local", false))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.local").value(false))
                    .andExpect(jsonPath("$.minutosTiempo").value(25));
        }
        enviar(post("/api/dt/partidos"), "profe", Map.of("rival", "Uno más")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Ya cargaste los 25 partidos de este mes. El cupo se renueva el 15/11."));
        mvc.perform(get("/api/dt/partidos").with(como("profe"))).andExpect(jsonPath("$", hasSize(25)));
        // Eliminar un partido no devuelve el lugar en el cupo.
        long uno = partidos.findAll().getFirst().getId();
        mvc.perform(delete("/api/dt/partidos/" + uno).with(como("profe")).with(csrf())).andExpect(status().isNoContent());
        enviar(post("/api/dt/partidos"), "profe", Map.of("rival", "Otra vez")).andExpect(status().isConflict());
        // El mes siguiente de la suscripción vuelve a tener cupo.
        reloj.ahora = Instant.parse("2026-11-15T12:00:00Z");
        enviar(post("/api/dt/partidos"), "profe", Map.of("rival", "Nuevo mes")).andExpect(status().isCreated());
    }

    @Test
    void elPlanFreeEsDeUnEquipoYCincoPartidosPorMesHastaFinDe2026() throws Exception {
        dt("gratis", "DT_FREE");
        equipo("gratis", "Mi equipo");
        enviar(post("/api/dt/equipos"), "gratis", Map.of("nombre", "Otro")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Tu plan DT free permite 1 equipo. Para sumar otro, borrá uno sin "
                        + "partidos o pasate al plan mensual."));
        for (int i = 0; i < 5; i++) {
            enviar(post("/api/dt/partidos"), "gratis", Map.of("rival", "Rival " + i)).andExpect(status().isCreated());
        }
        enviar(post("/api/dt/partidos"), "gratis", Map.of("rival", "Uno más")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Ya cargaste los 5 partidos de este mes. El cupo se renueva el 15/11."));
        // El 31/12/2026 (otro mes de la suscripción) todavía vale; el 1/1/2027 ya no se pueden empezar partidos.
        reloj.ahora = Instant.parse("2026-12-31T20:00:00Z");
        enviar(post("/api/dt/partidos"), "gratis", Map.of("rival", "Fin de año")).andExpect(status().isCreated());
        reloj.ahora = Instant.parse("2027-01-01T13:00:00Z");
        enviar(post("/api/dt/partidos"), "gratis", Map.of("rival", "Año nuevo")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Tu plan DT free terminó el 31/12/2026. Pasate al plan mensual "
                        + "para seguir cargando partidos."));
    }

    @Test
    void accionesDeTodosLosPartidosDelEquipo() throws Exception {
        long uno = crear("profe", "HURACAN");
        long dos = crear("profe", "GEBA A");
        enviar(post("/api/dt/partidos/" + uno + "/acciones"), "profe", List.of(accion(delantero, "TIRO", "GOL", 1, 30),
                accion(arquero, "ATAJADA", null, 1, 40))).andExpect(status().isOk());
        Map<String, Object> borrada = accion(delantero, "FALTA", "COMETIDA", 1, 50);
        enviar(post("/api/dt/partidos/" + dos + "/acciones"), "profe", List.of(accion(delantero, "PERDIDA", "RIVAL", 1, 10),
                borrada)).andExpect(status().isOk());
        mvc.perform(delete("/api/dt/partidos/" + dos + "/acciones/" + borrada.get("id")).with(como("profe")).with(csrf()))
                .andExpect(status().isOk());
        // Un partido de otro equipo del DT no entra.
        long infantil = equipo("profe", "Infantil");
        enviar(post("/api/dt/partidos"), "profe", Map.of("equipo", infantil, "rival", "SAN JUAN"))
                .andExpect(status().isCreated());

        mvc.perform(get("/api/dt/acciones").param("equipo", String.valueOf(primera)).with(como("profe")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))   // la borrada no cuenta
                .andExpect(jsonPath("$[0].partidoId").value(uno))
                .andExpect(jsonPath("$[0].jugadorNombre").value("Pedro Delantero"))
                .andExpect(jsonPath("$[2].tipo").value("PERDIDA"));
        mvc.perform(get("/api/dt/acciones").param("equipo", String.valueOf(infantil)).with(como("profe")))
                .andExpect(jsonPath("$", hasSize(0)));
        // Otro DT no ve las acciones de este.
        mvc.perform(get("/api/dt/acciones").param("equipo", String.valueOf(primera)).with(como("otro")))
                .andExpect(status().isNotFound());
    }

    @Test
    void informeDelPartidoYDelPeriodo() throws Exception {
        String body = enviar(post("/api/dt/partidos"), "profe", Map.of("equipo", primera, "rival", "HURACAN",
                "fecha", "2026-10-18", "torneo", "  Apertura   2026 ")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.torneo").value("Apertura 2026"))
                .andReturn().getResponse().getContentAsString();
        long id = json.readTree(body).get("id").asLong();
        String base = "/api/dt/partidos/" + id;
        Map<String, Object> refuerzo = accion(0, "FALTA", "COMETIDA", 2, 100);
        refuerzo.put("jugadorNombre", "Refuerzo");
        enviar(post(base + "/acciones"), "profe", List.of(accion(delantero, "TIRO", "GOL", 1, 30),
                accion(delantero, "TIRO", "NO_GOL", 1, 60), accion(delantero, "PENAL_FAVOR", "GOL", 1, 90),
                accion(delantero, "LIBRE_FAVOR", "NO_GOL", 1, 120), accion(arquero, "ATAJADA", null, 1, 150),
                accion(arquero, "PENAL_CONTRA", "ATAJADO", 1, 180), accion(arquero, "GOL_RECIBIDO", null, 1, 210),
                accion(delantero, "PERDIDA", "PROPIO", 1, 240), accion(delantero, "RECUPERACION", "RIVAL", 1, 270),
                accion(delantero, "FALTA", "RECIBIDA", 2, 10), accion(delantero, "TARJETA", "AZUL", 2, 50), refuerzo))
                .andExpect(status().isOk());

        mvc.perform(get(base + "/informe").with(como("profe"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.titulo").value("VELEZ 2 - 1 HURACAN"))
                .andExpect(jsonPath("$.detalle").value("Primera A · Local · Apertura 2026 · 18/10/2026"))
                .andExpect(jsonPath("$.totales.goles").value(2))
                .andExpect(jsonPath("$.totales.golesRecibidos").value(1))
                .andExpect(jsonPath("$.totales.tiros").value(2))
                .andExpect(jsonPath("$.totales.efectividadTiros").value(50))
                .andExpect(jsonPath("$.totales.atajadas").value(2))
                .andExpect(jsonPath("$.totales.perdidas").value(1))
                .andExpect(jsonPath("$.totales.recuperaciones").value(1))
                .andExpect(jsonPath("$.totales.faltasRecibidas").value(1))
                .andExpect(jsonPath("$.totales.faltasCometidas").value(1))
                .andExpect(jsonPath("$.totales.azules").value(1))
                .andExpect(jsonPath("$.totales.efectividadPenales").value(100))
                .andExpect(jsonPath("$.totales.efectividadLibres").value(0))
                .andExpect(jsonPath("$.jugadores[*].nombre").value(contains("Juan Arquero", "Pedro Delantero", "Refuerzo")))
                .andExpect(jsonPath("$.jugadores[0].arquero").value(true))
                .andExpect(jsonPath("$.eventos", hasSize(12)))
                .andExpect(jsonPath("$.eventos[0].minuto").value("1T 00:30"))
                .andExpect(jsonPath("$.eventos[0].accion").value("Tiro al arco: Gol"))
                .andExpect(jsonPath("$.eventos[11].jugador").value("Refuerzo"));
        // Solo un jugador: sus métricas y sus eventos.
        mvc.perform(get(base + "/informe").param("jugador", "j" + delantero).with(como("profe")))
                .andExpect(jsonPath("$.jugador.nombre").value("Pedro Delantero"))
                .andExpect(jsonPath("$.totales.goles").value(2))
                .andExpect(jsonPath("$.totales.golesRecibidos").value(0))
                .andExpect(jsonPath("$.jugadores", hasSize(1)))
                .andExpect(jsonPath("$.eventos", hasSize(8)));
        mvc.perform(get(base + "/informe").param("jugador", "j99").with(como("profe")))
                .andExpect(status().isBadRequest());
        mvc.perform(get(base + "/informe").with(como("otro"))).andExpect(status().isNotFound());
        byte[] pdf = mvc.perform(get(base + "/informe.pdf").with(como("profe"))).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("Content-Disposition", containsString("informe-huracan-2026-10-18.pdf")))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(pdf, 0, 4)).isEqualTo("%PDF");

        // Los informes por período solo cuentan los partidos finalizados.
        String eq = String.valueOf(primera);
        mvc.perform(get("/api/dt/informes").param("equipo", eq).param("periodo", "MES").param("valor", "2026-10")
                        .with(como("profe")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("No hay partidos finalizados en Octubre 2026."));
        for (String a : List.of("INICIAR", "FIN_TIEMPO", "INICIAR", "FIN_TIEMPO")) {
            enviar(post(base + "/reloj"), "profe", Map.of("accion", a)).andExpect(status().isOk());
        }
        mvc.perform(get("/api/dt/informes").param("equipo", eq).param("periodo", "MES").param("valor", "2026-10")
                        .with(como("profe")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.titulo").value("Octubre 2026"))
                .andExpect(jsonPath("$.detalle").value("Primera A · 1 partido"))
                .andExpect(jsonPath("$.balance.ganados").value(1))
                .andExpect(jsonPath("$.partidos[0].resultado").value("G"))
                .andExpect(jsonPath("$.eventos", hasSize(0)));
        mvc.perform(get("/api/dt/informes").param("periodo", "BIMESTRE").param("valor", "2026-5").with(como("profe")))
                .andExpect(jsonPath("$.titulo").value("Bimestre septiembre-octubre 2026"));
        mvc.perform(get("/api/dt/informes").param("periodo", "SEMESTRE").param("valor", "2026-2").with(como("profe")))
                .andExpect(jsonPath("$.titulo").value("2.º semestre 2026 (julio a diciembre)"));
        mvc.perform(get("/api/dt/informes").param("periodo", "CAMPEONATO").param("valor", "Apertura 2026")
                        .with(como("profe")))
                .andExpect(jsonPath("$.titulo").value("Campeonato Apertura 2026"))
                .andExpect(jsonPath("$.totales.goles").value(2));
        mvc.perform(get("/api/dt/informes").param("periodo", "CAMPEONATO").param("valor", "").with(como("profe")))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/dt/informes").param("periodo", "ANIO").param("valor", "2026").with(como("profe")))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/dt/informes").param("periodo", "MES").param("valor", "2026-13").with(como("profe")))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/dt/informes.pdf").param("periodo", "MES").param("valor", "2026-10")
                        .param("jugador", "j" + arquero).with(como("profe")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("informe-octubre-2026-juan-arquero.pdf")));
        // Otro DT no tiene equipos.
        mvc.perform(get("/api/dt/informes").param("periodo", "MES").param("valor", "2026-10").with(como("otro")))
                .andExpect(status().isBadRequest());
    }
}
