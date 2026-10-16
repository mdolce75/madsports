package com.rinkmetrics.dt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rinkmetrics.account.EquipoDt;
import com.rinkmetrics.account.Perfil;
import com.rinkmetrics.account.SuscripcionTypeRepository;
import com.rinkmetrics.account.Usuario;
import com.rinkmetrics.account.UsuarioRepository;
import com.rinkmetrics.catalog.CatalogService;
import com.rinkmetrics.player.RosterService;
import com.rinkmetrics.player.RosterService.PartidoEquipo;
import com.rinkmetrics.player.RosterService.PlantelJugador;
import com.rinkmetrics.sidgad.model.Competition;
import com.rinkmetrics.sidgad.model.Federacion;
import com.rinkmetrics.sidgad.model.Models.Match;
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
import java.time.LocalTime;
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

/** API de la planilla DT, con la base H2 en memoria, SIDGAD simulado y un reloj que se adelanta a mano. */
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

    static Match match(int idp, LocalDate fecha, String local, String visitante, Integer gl, Integer gv) {
        return new Match(idp, 1, fecha, LocalTime.of(20, 0), "Cancha", 1, 74, local, 2, 9, visitante, gl, gv);
    }

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
        when(roster.partidos(Federacion.FPP, 74, 2026, "PRIMERA", "MASCULINO")).thenReturn(List.of(
                new PartidoEquipo(APERTURA, match(9001, LocalDate.of(2026, 10, 1), "VELEZ", "HURACAN", 3, 2), true),
                new PartidoEquipo(APERTURA, match(9003, LocalDate.of(2026, 10, 25), "SAN JUAN", "VELEZ", null, null), false),
                new PartidoEquipo(APERTURA, match(9002, LocalDate.of(2026, 10, 18), "VELEZ", "LUJAN", null, null), true)));
        when(roster.plantel(Federacion.FPP, 74, 2026, "PRIMERA", "MASCULINO")).thenReturn(List.of(
                new PlantelJugador(10, "1", "Juan Arquero", true),
                new PlantelJugador(11, "7", "Pedro Delantero", false)));
        dt("profe");
        dt("otro");
    }

    void dt(String nombre) {
        Usuario u = new Usuario(nombre, "x", nombre + "@example.com", "Ana", "Pérez", 74, "VELEZ", "CAVS",
                EnumSet.of(Perfil.DTFPP), Instant.now());
        u.asignarEquiposDt(List.of(new EquipoDt("PRIMERA", "MASCULINO")));
        u.suscribir(suscripciones.findByCodigo("DT_MENSUAL").orElseThrow(), LocalDate.of(2026, 9, 15));
        usuarios.save(u);
    }

    static RequestPostProcessor como(String usuario) {
        return user(usuario).authorities(() -> "DTFPP");
    }

    ResultActions enviar(MockHttpServletRequestBuilder req, String usuario, Object body) throws Exception {
        return mvc.perform(req.with(como(usuario)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    long crear(String usuario, int idp) throws Exception {
        String body = enviar(post("/api/dt/partidos"), usuario, Map.of("idp", idp))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("id").asLong();
    }

    @Test
    void comboDeRivalesConLosProximosPrimero() throws Exception {
        mvc.perform(get("/api/dt/partidos-disponibles").with(como("profe")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].rival").value(contains("LUJAN", "SAN JUAN", "HURACAN")))
                .andExpect(jsonPath("$[1].local").value(false))
                .andExpect(jsonPath("$[2].jugado").value(true))
                .andExpect(jsonPath("$[0].minutosTiempo").value(25))
                .andExpect(jsonPath("$[0].torneo").value("Apertura"));
        mvc.perform(get("/api/dt/partidos-disponibles").param("categoria", "JUNIORS:A").param("rama", "FEMENINO")
                        .with(como("profe"))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Ese equipo no es uno de los tuyos."));
        mvc.perform(get("/api/dt/plantel").with(como("profe")))
                .andExpect(jsonPath("$[*].nombre").value(contains("Juan Arquero", "Pedro Delantero")));
    }

    @Test
    void soloElDtVeSusPartidos() throws Exception {
        long id = crear("profe", 9002);
        mvc.perform(get("/api/dt/partidos/" + id).with(como("profe"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.rival").value("LUJAN"))
                .andExpect(jsonPath("$.estado").value("PENDIENTE"))
                .andExpect(jsonPath("$.reloj.restante").value(1500));
        mvc.perform(get("/api/dt/partidos/" + id).with(como("otro"))).andExpect(status().isNotFound());
        enviar(post("/api/dt/partidos"), "otro", Map.of("idp", 9002)).andExpect(status().isConflict());
        // El mismo DT retoma el partido en vez de crear otro.
        enviar(post("/api/dt/partidos"), "profe", Map.of("idp", 9002)).andExpect(jsonPath("$.id").value(id));
        mvc.perform(get("/api/dt/partidos-disponibles").with(como("profe")))
                .andExpect(jsonPath("$[0].cargado").value(id));
        mvc.perform(get("/api/dt/partidos").with(user("c").authorities(() -> "CLUBFPP"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/dt/partidos").with(user("p").authorities(() -> "PLAYERFPP"))).andExpect(status().isForbidden());
        enviar(post("/api/dt/partidos"), "profe", Map.of("idp", 1234)).andExpect(status().isBadRequest());
    }

    @Test
    void relojYAccionesDelPartido() throws Exception {
        long id = crear("profe", 9002);
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
    void amistosoYCupoDe20PartidosPorMes() throws Exception {
        enviar(post("/api/dt/partidos"), "profe", Map.of()).andExpect(status().isBadRequest());
        for (int i = 0; i < 20; i++) {
            enviar(post("/api/dt/partidos"), "profe", Map.of("rival", "Amistoso " + i, "local", false))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.torneo").value("Amistoso"))
                    .andExpect(jsonPath("$.minutosTiempo").value(25));
        }
        enviar(post("/api/dt/partidos"), "profe", Map.of("rival", "Uno más")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Ya cargaste los 20 partidos de este mes. El cupo se renueva el 15/11."));
        mvc.perform(get("/api/dt/partidos").with(como("profe"))).andExpect(jsonPath("$", hasSize(20)));
        // El mes siguiente de la suscripción vuelve a tener cupo.
        reloj.ahora = Instant.parse("2026-11-15T12:00:00Z");
        enviar(post("/api/dt/partidos"), "profe", Map.of("rival", "Nuevo mes")).andExpect(status().isCreated());
    }
}
