package com.rinkmetrics.dt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rinkmetrics.account.EquipoDt;
import com.rinkmetrics.account.Perfil;
import com.rinkmetrics.account.SuscripcionTypeRepository;
import com.rinkmetrics.account.Usuario;
import com.rinkmetrics.account.UsuarioRepository;
import com.rinkmetrics.catalog.CatalogService;
import com.rinkmetrics.catalog.CatalogService.CatalogView;
import com.rinkmetrics.catalog.CatalogService.ClubView;
import com.rinkmetrics.catalog.CatalogService.LevelView;
import com.rinkmetrics.player.RosterService;
import com.rinkmetrics.player.RosterService.PlantelJugador;
import com.rinkmetrics.sidgad.model.Competition;
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
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** API de Métricas DT, con la base H2 en memoria, SIDGAD simulado y un reloj que se adelanta a mano. */
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

    static final Competition APERTURA = new Competition(500, "APERTURA PRIMERA MASCULINO", 3, 40,
            Competition.Torneo.APERTURA, new Competition.Category("PRIMERA", "MASCULINO"), 1, List.of(), 25);
    static final Competition INFANTIL = new Competition(501, "APERTURA INFANTIL MASCULINO", 3, 40,
            Competition.Torneo.APERTURA, new Competition.Category("INFANTIL", "MASCULINO"), 1, List.of(), 20);

    static LevelView nivel(String key, String... ramas) {
        return new LevelView(key, key, List.of(ramas));
    }

    /** Clubes de la FPP en 2026: Vélez (Primera A y B), Huracán, GEBA (A y B), Lujan (solo femenino) y SAN JUAN. */
    static final CatalogView CATALOGO = new CatalogView(2026, true, null, List.of(2026), List.of(), List.of(), List.of(
            new ClubView(74, "CAVS", "VELEZ", List.of(nivel("PRIMERA:A", "MASCULINO"), nivel("PRIMERA:B", "MASCULINO"),
                    nivel("INFANTIL", "MASCULINO"))),
            new ClubView(9, "CAH", "HURACAN", List.of(nivel("PRIMERA", "MASCULINO", "FEMENINO"))),
            new ClubView(12, "GEBA", "GEBA", List.of(nivel("PRIMERA:A", "MASCULINO"), nivel("PRIMERA:B", "MASCULINO"))),
            new ClubView(15, "LUJ", "LUJAN", List.of(nivel("PRIMERA", "FEMENINO"))),
            new ClubView(20, "SJ", "SAN JUAN", List.of(nivel("INFANTIL", "MASCULINO")))), "FPP");

    static Map<String, Object> accion(int jugadorId, String tipo, String resultado, int tiempo, int segundo) {
        Map<String, Object> m = new java.util.HashMap<>(Map.of("id", UUID.randomUUID().toString(),
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

    @MockitoBean CatalogService catalog;
    @MockitoBean RosterService roster;

    @BeforeEach
    void setUp() {
        acciones.deleteAll();
        partidos.deleteAll();
        usuarios.deleteAll();
        reloj.ahora = INICIO;
        when(catalog.defaultYear()).thenReturn(2026);
        when(catalog.view(Federacion.FPP, 2026)).thenReturn(CATALOGO);
        when(catalog.competitions(Federacion.FPP, 2026)).thenReturn(List.of(APERTURA, INFANTIL));
        when(roster.plantel(Federacion.FPP, 74, 2026, "PRIMERA:A", "MASCULINO")).thenReturn(List.of(
                new PlantelJugador(10, "1", "Juan Arquero", true),
                new PlantelJugador(11, "7", "Pedro Delantero", false)));
        dt("profe");
        dt("otro");
    }

    void dt(String nombre) {
        dt(nombre, "DT_MENSUAL");
    }

    void dt(String nombre, String plan) {
        Usuario u = new Usuario(nombre, "x", nombre + "@example.com", "Ana", "Pérez", 74, "VELEZ", "CAVS",
                EnumSet.of(Perfil.DTFPP), Instant.now());
        u.asignarEquiposDt(List.of(new EquipoDt("PRIMERA:A", "MASCULINO"), new EquipoDt("INFANTIL", "MASCULINO")));
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

    static Map<String, Object> partido(String rival) {
        return Map.of("rival", rival, "fecha", "2026-10-18", "local", true);
    }

    long crear(String usuario, String rival) throws Exception {
        String body = enviar(post("/api/dt/partidos"), usuario, partido(rival))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("id").asLong();
    }

    @Test
    void comboConLosRivalesDeLaCategoria() throws Exception {
        // Primera A de Vélez: los equipos de Primera masculino de los otros clubes, y Vélez B; no Lujan (solo femenino).
        mvc.perform(get("/api/dt/rivales").with(como("profe")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].nombre").value(contains("GEBA A", "GEBA B", "HURACAN", "VELEZ B")))
                .andExpect(jsonPath("$[2].clubId").value(9));
        mvc.perform(get("/api/dt/rivales").param("categoria", "INFANTIL").param("rama", "MASCULINO").with(como("profe")))
                .andExpect(jsonPath("$[*].nombre").value(contains("SAN JUAN")));
        mvc.perform(get("/api/dt/rivales").param("categoria", "JUNIORS:A").param("rama", "FEMENINO")
                        .with(como("profe"))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Ese equipo no es uno de los tuyos."));
        mvc.perform(get("/api/dt/plantel").with(como("profe")))
                .andExpect(jsonPath("$[*].nombre").value(contains("Juan Arquero", "Pedro Delantero")));
    }

    @Test
    void soloElDtVeSusPartidos() throws Exception {
        long id = crear("profe", "HURACAN");
        mvc.perform(get("/api/dt/partidos/" + id).with(como("profe"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.rival").value("HURACAN"))
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
                .andExpect(jsonPath("$.detail").value("Elegí el rival."));
        // La duración de los tiempos sale de la categoría: Infantil juega de a 20'.
        enviar(post("/api/dt/partidos"), "profe", Map.of("categoria", "INFANTIL", "rama", "MASCULINO",
                "rival", "SAN JUAN")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.minutosTiempo").value(20))
                .andExpect(jsonPath("$.fecha").value("2026-10-16"));
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

        Map<String, Object> gol = accion(11, "TIRO", "GOL", 1, 390);
        Map<String, Object> refuerzo = accion(0, "TARJETA", "AZUL", 1, 500);
        refuerzo.put("jugadorNombre", "Refuerzo");
        List<Map<String, Object>> tanda = List.of(gol, accion(10, "ATAJADA", null, 1, 400),
                accion(10, "GOL_RECIBIDO", null, 1, 420), refuerzo);
        enviar(post(base + "/acciones"), "profe", tanda).andExpect(status().isOk())
                .andExpect(jsonPath("$.golesFavor").value(1))
                .andExpect(jsonPath("$.golesContra").value(1))
                .andExpect(jsonPath("$.acciones", hasSize(4)))
                .andExpect(jsonPath("$.acciones[0].jugadorNombre").value("Pedro Delantero"));
        // Reenviar la misma tanda (sin señal) no duplica.
        enviar(post(base + "/acciones"), "profe", tanda).andExpect(jsonPath("$.acciones", hasSize(4)));

        enviar(post(base + "/acciones"), "profe", List.of(accion(11, "PERDIDA", "GOL", 1, 10)))
                .andExpect(status().isBadRequest());
        enviar(post(base + "/acciones"), "profe", List.of(accion(99, "FALTA", "COMETIDA", 1, 10)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("El jugador 99 no está en el plantel."));
        enviar(post(base + "/acciones"), "profe", List.of(accion(11, "FALTA", "COMETIDA", 1, 1501)))
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
        enviar(post(base + "/acciones"), "profe", List.of(accion(11, "FALTA", "RECIBIDA", 2, 10)))
                .andExpect(status().isConflict());
        enviar(post(base + "/reabrir"), "profe", Map.of()).andExpect(jsonPath("$.estado").value("TERMINADO"));
    }

    @Test
    void cupoDe20PartidosPorMes() throws Exception {
        for (int i = 0; i < 20; i++) {
            enviar(post("/api/dt/partidos"), "profe", Map.of("rival", "Rival " + i, "local", false))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.local").value(false))
                    .andExpect(jsonPath("$.minutosTiempo").value(25));
        }
        enviar(post("/api/dt/partidos"), "profe", Map.of("rival", "Uno más")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Ya cargaste los 20 partidos de este mes. El cupo se renueva el 15/11."));
        mvc.perform(get("/api/dt/partidos").with(como("profe"))).andExpect(jsonPath("$", hasSize(20)));
        // El mes siguiente de la suscripción vuelve a tener cupo.
        reloj.ahora = Instant.parse("2026-11-15T12:00:00Z");
        enviar(post("/api/dt/partidos"), "profe", Map.of("rival", "Nuevo mes")).andExpect(status().isCreated());
    }

    @Test
    void elPlanFreeNoTieneLimiteDePartidosHastaFinDe2026() throws Exception {
        dt("gratis", "DT_FREE");
        // Más que los 20 del plan mensual: el free no tiene límite.
        for (int i = 0; i < 25; i++) {
            enviar(post("/api/dt/partidos"), "gratis", Map.of("rival", "Rival " + i)).andExpect(status().isCreated());
        }
        // El 31/12/2026 todavía vale; el 1/1/2027 ya no se pueden empezar partidos.
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
        enviar(post("/api/dt/partidos/" + uno + "/acciones"), "profe", List.of(accion(11, "TIRO", "GOL", 1, 30),
                accion(10, "ATAJADA", null, 1, 40))).andExpect(status().isOk());
        Map<String, Object> borrada = accion(11, "FALTA", "COMETIDA", 1, 50);
        enviar(post("/api/dt/partidos/" + dos + "/acciones"), "profe", List.of(accion(11, "PERDIDA", "RIVAL", 1, 10),
                borrada)).andExpect(status().isOk());
        mvc.perform(delete("/api/dt/partidos/" + dos + "/acciones/" + borrada.get("id")).with(como("profe")).with(csrf()))
                .andExpect(status().isOk());
        // Un partido de otro equipo del DT no entra.
        enviar(post("/api/dt/partidos"), "profe", Map.of("categoria", "INFANTIL", "rama", "MASCULINO", "rival", "SAN JUAN"))
                .andExpect(status().isCreated());

        mvc.perform(get("/api/dt/acciones").with(como("profe"))).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))   // la borrada no cuenta
                .andExpect(jsonPath("$[0].partidoId").value(uno))
                .andExpect(jsonPath("$[0].jugadorNombre").value("Pedro Delantero"))
                .andExpect(jsonPath("$[2].tipo").value("PERDIDA"));
        // Otro DT no ve las acciones de este.
        mvc.perform(get("/api/dt/acciones").with(como("otro"))).andExpect(jsonPath("$", hasSize(0)));
    }
}
