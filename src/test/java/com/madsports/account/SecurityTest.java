package com.madsports.account;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.madsports.catalog.CatalogService;
import com.madsports.catalog.CatalogService.ClubView;
import com.madsports.sidgad.model.Federacion;
import com.madsports.catalog.RosterService;
import com.madsports.catalog.RosterService.RosterPlayer;
import com.madsports.report.ReportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.core.env.Environment;
import jakarta.servlet.http.Cookie;

import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Registro, login y acceso por perfil, con la base H2 en memoria y SIDGAD simulado. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:seguridad;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class SecurityTest {

    static final ClubView VELEZ = new ClubView(74, "CAVS", "VELEZ", List.of(
            new CatalogService.LevelView("JUNIORS:A", "Juniors A", List.of("MASCULINO", "FEMENINO")),
            new CatalogService.LevelView("PRIMERA", "Primera", List.of("MASCULINO"))));
    static final ClubView MURIALDO = new ClubView(3, "LM", "LEONARDO MURIALDO", List.of(
            new CatalogService.LevelView("JUVENIL:A", "Juvenil A", List.of("MASCULINO"))));

    @Autowired MockMvc mvc;
    @Autowired UsuarioRepository usuarios;
    @Autowired PasswordEncoder encoder;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired Environment env;

    @MockitoBean CatalogService catalog;
    @MockitoBean RosterService roster;
    @MockitoBean ReportService reports;
    @MockitoBean com.madsports.player.PlayerReportService playerReports;
    @Autowired SuscripcionTypeRepository suscripciones;
    @MockitoBean Mailer mailer;
    @Autowired org.thymeleaf.ITemplateEngine templates;

    /** Mailer de verdad (sin SMTP) para revisar el HTML; el de la app está simulado para capturar los envíos. */
    Mailer realMailer;

    @BeforeEach
    void realMailer() {
        realMailer = new Mailer(null, templates, "", "RinkMetrics <info@madsportsapp.com>");
    }

    @BeforeEach
    void setUp() {
        usuarios.deleteAll();
        when(catalog.defaultYear()).thenReturn(2026);
        when(catalog.checkYear(any())).thenReturn(2026);
        when(catalog.club(Federacion.FPP, 74, 2026)).thenReturn(VELEZ);
        when(catalog.club(eq(Federacion.FPP), eq(99), eq(2026)))
                .thenThrow(new IllegalArgumentException("El club 99 no juega en la FPP en 2026."));
        when(roster.find(Federacion.FPP, 74, 2026, "PRIMERA", "MASCULINO", 3851))
                .thenReturn(Optional.of(new RosterPlayer(3851, "Ignacio J Graiño", "Ignacio J", "Graiño",
                        "Primera Masculino")));
        // AMP (Mendoza): Leonardo Murialdo; Vélez no juega en la AMP.
        when(catalog.club(Federacion.AMP, 3, 2026)).thenReturn(MURIALDO);
        when(catalog.club(eq(Federacion.AMP), eq(74), eq(2026)))
                .thenThrow(new IllegalArgumentException("El club 74 no juega en la AMP en 2026."));
        when(roster.find(Federacion.AMP, 3, 2026, "JUVENIL", "MASCULINO", 6188))
                .thenReturn(Optional.of(new RosterPlayer(6188, "Victor Daniel Terraza Gomez", "Victor Daniel",
                        "Terraza Gomez", "Juvenil Masculino")));
    }

    Map<String, Object> club(String usuario) {
        Map<String, Object> m = new HashMap<>(Map.of("perfil", "CLUBFPP", "usuario", usuario, "clave", "clave-segura",
                "email", usuario + "@example.com", "clubId", 74, "suscripcion", "CLUB_FREE"));
        return m;
    }

    /** El jugador no manda nombre ni apellido: se toman del jugador elegido. */
    Map<String, Object> player(String usuario) {
        Map<String, Object> m = club(usuario);
        m.putAll(Map.of("perfil", "PLAYERFPP", "categoria", "PRIMERA", "rama", "MASCULINO",
                "jugadorId", 3851, "suscripcion", "PLAYER_MENSUAL"));
        return m;
    }

    ResultActions register(Map<String, Object> body) throws Exception {
        return mvc.perform(post("/api/registro").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    ResultActions login(String usuario, String clave) throws Exception {
        return mvc.perform(post("/api/login").with(csrf()).param("usuario", usuario).param("clave", clave));
    }

    /** Ingreso desde la pantalla de un producto (REPORTS o METRICS). */
    ResultActions loginEn(String usuario, String producto) throws Exception {
        return mvc.perform(post("/api/login").with(csrf()).param("usuario", usuario).param("clave", "clave-segura")
                .param("producto", producto));
    }

    /** Token del último mail enviado a esa dirección con un enlace a {@code path}. */
    String token(String email, String path) {
        ArgumentCaptor<MailContent> mail = ArgumentCaptor.forClass(MailContent.class);
        verify(mailer, atLeastOnce()).send(mail.capture());
        String last = mail.getAllValues().stream().filter(m -> m.to().equals(email)).reduce((a, b) -> b)
                .orElseThrow().url();
        Matcher m = Pattern.compile(Pattern.quote(path) + "\\?(?:[^#]*&)?token=([A-Za-z0-9_-]+)").matcher(last);
        assertThat(m.find()).as("enlace en el mail:\n%s", last).isTrue();
        return m.group(1);
    }

    /** Confirma el email con el enlace del mail: lleva al ingreso del producto de la cuenta. */
    void confirmar(String email) throws Exception {
        mvc.perform(get("/api/verificar").param("token", token(email, "/api/verificar")))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", org.hamcrest.Matchers.endsWith("login.html?verificado")));
    }

    Usuario saved(String usuario, Perfil perfil) {
        return usuarios.save(new Usuario(usuario, encoder.encode("clave-segura"), usuario + "@example.com", "Ana",
                "Pérez", 74, "VELEZ", "CAVS", EnumSet.of(perfil), Instant.now()));
    }

    /** Ingreso con "Mantener la sesión iniciada": devuelve la respuesta (con la cookie RECORDARME y la sesión). */
    MvcResult loginRecordando(String usuario) throws Exception {
        return mvc.perform(post("/api/login").with(csrf()).param("usuario", usuario).param("clave", "clave-segura")
                .param("recordarme", "true")).andExpect(status().isOk()).andReturn();
    }

    int recordados(String usuario) {
        return jdbc.queryForObject("select count(*) from persistent_logins where username = ?", Integer.class, usuario);
    }

    @Test
    void healthCheckPublicoSinDetalles() throws Exception {
        // Sin sesión: estado general y de cada componente; SIDGAD queda UNKNOWN hasta el primer chequeo diario.
        mvc.perform(get("/actuator/health")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.db.status").value("UP"))
                .andExpect(jsonPath("$.components.sidgad.status").value("UNKNOWN"))
                .andExpect(jsonPath("$.components.db.details").doesNotExist())
                .andExpect(jsonPath("$.components.sidgad.details").doesNotExist());
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        // El resto de los endpoints no se expone: ni con sesión existe.
        mvc.perform(get("/actuator/env").with(user("ana"))).andExpect(status().isNotFound());
    }

    @Test
    void recordarmeVuelveAEntrarAunqueSePierdaLaSesion() throws Exception {
        register(club("ana")).andExpect(status().isCreated());
        confirmar("ana@example.com");
        Cookie c = loginRecordando("ana").getResponse().getCookie("RECORDARME");
        assertThat(c).isNotNull();
        assertThat(c.getMaxAge()).isEqualTo(30 * 24 * 3600);
        assertThat(c.isHttpOnly()).isTrue();
        assertThat(recordados("ana")).isEqualTo(1);

        // Sin sesión (venció, se cerró el navegador o se reinició la app): la cookie vuelve a entrar sola.
        MvcResult r = mvc.perform(get("/api/sesion").cookie(c))
                .andExpect(jsonPath("$.autenticado").value(true))
                .andExpect(jsonPath("$.usuario").value("ana")).andReturn();
        // El token cambia en cada uso; con el nuevo, también las pantallas y la API del perfil.
        Cookie nueva = r.getResponse().getCookie("RECORDARME");
        assertThat(nueva.getValue()).isNotEqualTo(c.getValue());
        mvc.perform(get("/rinkreports/club.html").cookie(nueva)).andExpect(status().isOk());

        // Sin marcar la casilla no queda recordado.
        register(club("beto")).andExpect(status().isCreated());
        confirmar("beto@example.com");
        assertThat(login("beto", "clave-segura").andExpect(status().isOk()).andReturn().getResponse()
                .getCookie("RECORDARME")).isNull();
        assertThat(recordados("beto")).isZero();
    }

    @Test
    void salirYCambiarLaClaveDejanDeRecordar() throws Exception {
        register(club("ana")).andExpect(status().isCreated());
        confirmar("ana@example.com");

        // Salir borra el dispositivo recordado.
        MvcResult r = loginRecordando("ana");
        Cookie c = r.getResponse().getCookie("RECORDARME");
        mvc.perform(post("/api/logout").with(csrf()).session((MockHttpSession) r.getRequest().getSession()).cookie(c))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getCookie("RECORDARME").getMaxAge()).isZero());
        assertThat(recordados("ana")).isZero();
        mvc.perform(get("/api/sesion").cookie(c)).andExpect(jsonPath("$.autenticado").value(false));

        // Cambiar la clave también: hay que volver a ingresar con la nueva en cada dispositivo.
        Cookie otra = loginRecordando("ana").getResponse().getCookie("RECORDARME");
        mvc.perform(post("/api/clave/olvido").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"ana@example.com\"}")).andExpect(status().isOk());
        String token = token("ana@example.com", "/restablecer.html");
        mvc.perform(post("/api/clave/restablecer").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"%s\",\"clave\":\"otra-clave-segura\"}".formatted(token)))
                .andExpect(status().isOk());
        assertThat(recordados("ana")).isZero();
        mvc.perform(get("/api/sesion").cookie(otra)).andExpect(jsonPath("$.autenticado").value(false));
    }

    @Test
    void sinConfirmarElEmailTampocoQuedaRecordado() throws Exception {
        register(club("ana")).andExpect(status().isCreated());
        MvcResult r = mvc.perform(post("/api/login").with(csrf()).param("usuario", "ana").param("clave", "clave-segura")
                        .param("recordarme", "true"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("EMAIL_NO_VERIFICADO")).andReturn();
        // Si el ingreso llegó a crear la cookie, la respuesta la borra después (el navegador aplica la última).
        Cookie ultima = java.util.Arrays.stream(r.getResponse().getCookies())
                .filter(x -> x.getName().equals("RECORDARME")).reduce((a, b) -> b).orElse(null);
        assertThat(ultima == null || ultima.getMaxAge() == 0).isTrue();
        assertThat(recordados("ana")).isZero();
    }

    @Test
    void laSesionDura8HorasSinActividad() {
        assertThat(env.getProperty("server.servlet.session.timeout")).isEqualTo("8h");
    }

    @Test
    void sinSesionLasPaginasVanAlLoginYLaApiResponde401() throws Exception {
        // La home es pública (con los informes de ejemplo); la pantalla de club pide sesión.
        mvc.perform(get("/")).andExpect(status().isOk());
        mvc.perform(get("/api/ejemplo.pdf")).andExpect(status().isOk());
        // La home es la de Mad Sports; su dirección vieja lleva a la home.
        mvc.perform(get("/index.html")).andExpect(content().string(org.hamcrest.Matchers.containsString("Mad Sports")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("href=\"/rinkreports\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("href=\"/rinkmetrics\"")));
        mvc.perform(get("/madsports")).andExpect(redirectedUrl("/"));
        mvc.perform(get("/madsports.html")).andExpect(redirectedUrl("/"));
        // La portada de cada producto, pública.
        mvc.perform(get("/rinkreports")).andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl("/rinkreports/index.html"));
        mvc.perform(get("/rinkmetrics")).andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl("/dt-inicio.html"));
        // Al ingreso, con la pantalla a la que volver después.
        // Las pantallas de RinkReports van a su ingreso; las de RinkMetrics, al suyo.
        mvc.perform(get("/rinkreports/club.html")).andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/rinkreports/login.html?volver=%2Frinkreports%2Fclub.html"));
        mvc.perform(get("/rinkreports/jugador.html"))
                .andExpect(redirectedUrl("/rinkreports/login.html?volver=%2Frinkreports%2Fjugador.html"));
        mvc.perform(get("/dt.html")).andExpect(redirectedUrl("/login.html?volver=%2Fdt.html"));
        mvc.perform(get("/rinkreports/login.html")).andExpect(status().isOk());
        mvc.perform(get("/rinkreports/registro.html")).andExpect(status().isOk());
        // Las direcciones viejas de los informes llevan a RinkReports.
        mvc.perform(get("/club.html")).andExpect(redirectedUrl("/rinkreports/club.html"));
        mvc.perform(get("/jugador.html")).andExpect(redirectedUrl("/rinkreports/jugador.html"));
        mvc.perform(get("/informes.html")).andExpect(redirectedUrl("/rinkreports"));
        // Los ejemplos de RinkReports, públicos: en pantalla, PDF y Excel.
        mvc.perform(get("/rinkreports/ejemplo.html")).andExpect(status().isOk());
        mvc.perform(get("/login.html")).andExpect(status().isOk());
        // Las presentaciones de las dos secciones (Informes y DT) se ven sin sesión.
        mvc.perform(get("/dt-inicio.html")).andExpect(status().isOk());
        // La demo de RinkMetrics y sus capturas, también. La demo es la misma página que /dt.html (sin copia).
        mvc.perform(get("/dt-demo.html")).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/js/dt/main.js")));
        mvc.perform(get("/por-que.html")).andExpect(status().isOk());
        mvc.perform(get("/img/demo/carga-web.png")).andExpect(status().isOk());
        mvc.perform(get("/registro.html")).andExpect(status().isOk());
        mvc.perform(get("/api/ejemplo-jugador.pdf")).andExpect(status().isOk());
        mvc.perform(get("/api/informes/" + UUID.randomUUID())).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/sesion")).andExpect(status().isOk()).andExpect(jsonPath("$.autenticado").value(false));
    }

    @Test
    void registroDeClubYLogin() throws Exception {
        register(club("Ana.Club")).andExpect(status().isCreated()).andExpect(jsonPath("$.usuario").value("ana.club"));
        register(club("ana.club")).andExpect(status().isConflict());

        Usuario u = usuarios.findByUsuario("ana.club").orElseThrow();
        assertThat(u.getPerfiles()).containsExactly(Perfil.CLUBFPP);
        assertThat(u.getClave()).isNotEqualTo("clave-segura").startsWith("$2");
        assertThat(u.getClubNombre()).isEqualTo("VELEZ");
        // El plan elegido queda como suscripción desde hoy.
        assertThat(u.getSuscripcionType().getCodigo()).isEqualTo("CLUB_FREE");
        assertThat(u.getSuscripcionDesde()).isEqualTo(java.time.LocalDate.now());
        assertThat(u.getSuscripcionHasta()).isEqualTo("2026-12-31");
        assertThat(u.getNombre()).isNull();   // el registro no pide nombre ni apellido

        // Sin confirmar el email no se ingresa (con la clave correcta se avisa por qué).
        login("ana.club", "clave-segura").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("EMAIL_NO_VERIFICADO"));
        confirmar("Ana.Club@example.com");
        assertThat(usuarios.findByUsuario("ana.club").orElseThrow().isEmailVerificado()).isTrue();
        login("ANA.CLUB", "clave-segura").andExpect(status().isOk()).andExpect(jsonPath("$.inicio").value("/rinkreports/club.html"));
        login("ana.club", "otra-clave").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Usuario o clave incorrectos."));
    }

    @Test
    void registroDeJugadorConElJugadorDeLaFpp() throws Exception {
        Map<String, Object> sinJugador = player("juan");
        sinJugador.remove("jugadorId");
        register(sinJugador).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Elegí tu nombre en la lista de jugadores del club."));

        Map<String, Object> otroJugador = player("juan");
        otroJugador.put("jugadorId", 1);
        register(otroJugador).andExpect(status().isBadRequest());

        register(player("juan")).andExpect(status().isCreated());
        Usuario u = usuarios.findByUsuario("juan").orElseThrow();
        assertThat(u.getPerfiles()).containsExactly(Perfil.PLAYERFPP);
        assertThat(u.getJugadorId()).isEqualTo(3851);
        assertThat(u.getJugadorNombre()).isEqualTo("Ignacio J Graiño");
        assertThat(u.getSuscripcionType().getCodigo()).isEqualTo("PLAYER_MENSUAL");
        assertThat(u.getNombre()).isEqualTo("Ignacio J");
        assertThat(u.getApellido()).isEqualTo("Graiño");
        assertThat(u.getCategoria()).isEqualTo("PRIMERA");
        assertThat(u.getRama()).isEqualTo("MASCULINO");

        // Un jugador, un usuario.
        Map<String, Object> mismoJugador = player("juan2");
        register(mismoJugador).andExpect(status().isConflict());

        confirmar("juan@example.com");
        login("juan", "clave-segura").andExpect(jsonPath("$.inicio").value("/rinkreports/jugador.html"));
    }

    @Test
    void registroDeClubYJugadorDeLaAmp() throws Exception {
        Map<String, Object> clubAmp = club("murialdo");
        clubAmp.putAll(Map.of("perfil", "CLUBAMP", "clubId", 3));
        register(clubAmp).andExpect(status().isCreated());
        Usuario club = usuarios.findByUsuario("murialdo").orElseThrow();
        assertThat(club.getPerfiles()).containsExactly(Perfil.CLUBAMP);
        assertThat(club.federacion()).isEqualTo(Federacion.AMP);
        assertThat(club.getClubNombre()).isEqualTo("LEONARDO MURIALDO");

        // El club tiene que ser de la AMP: Vélez es de la FPP.
        Map<String, Object> velezEnAmp = club("velez.amp");
        velezEnAmp.put("perfil", "CLUBAMP");
        register(velezEnAmp).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("El club 74 no juega en la AMP en 2026."));

        Map<String, Object> jugadorAmp = player("terraza");
        jugadorAmp.putAll(Map.of("perfil", "PLAYERAMP", "clubId", 3, "categoria", "JUVENIL", "jugadorId", 6188));
        register(jugadorAmp).andExpect(status().isCreated());
        Usuario jugador = usuarios.findByUsuario("terraza").orElseThrow();
        assertThat(jugador.getPerfiles()).containsExactly(Perfil.PLAYERAMP);
        assertThat(jugador.getJugadorNombre()).isEqualTo("Victor Daniel Terraza Gomez");

        confirmar("murialdo@example.com");
        login("murialdo", "clave-segura").andExpect(jsonPath("$.inicio").value("/rinkreports/club.html"));
        mvc.perform(get("/api/sesion").with(user("murialdo").authorities(() -> "CLUBAMP")))
                .andExpect(jsonPath("$.federacion").value("AMP"));
        confirmar("terraza@example.com");
        login("terraza", "clave-segura").andExpect(jsonPath("$.inicio").value("/rinkreports/jugador.html"));
    }

    @Test
    void lasPantallasAceptanLosPerfilesDeLasDosFederaciones() throws Exception {
        mvc.perform(get("/rinkreports/club.html").with(user("a").authorities(() -> "CLUBAMP"))).andExpect(status().isOk());
        mvc.perform(get("/rinkreports/jugador.html").with(user("a").authorities(() -> "PLAYERAMP"))).andExpect(status().isOk());
        // Cada perfil, solo su pantalla.
        mvc.perform(get("/rinkreports/jugador.html").with(user("a").authorities(() -> "CLUBAMP")))
                .andExpect(status().is3xxRedirection());
        mvc.perform(post("/api/informes").with(user("a").authorities(() -> "PLAYERAMP")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    Map<String, Object> dt(String usuario) {
        Map<String, Object> m = club(usuario);
        m.putAll(Map.of("perfil", "DTFPP", "suscripcion", "DT_FREE"));
        return m;
    }

    @Test
    void registroDeDtSinEquipos() throws Exception {
        // El DT ya no elige equipos al registrarse: los arma después en la sección Equipo.
        register(dt("profe")).andExpect(status().isCreated());
        Usuario u = usuarios.findByUsuario("profe").orElseThrow();
        assertThat(u.getPerfiles()).containsExactly(Perfil.DTFPP);
        assertThat(u.esDt()).isTrue();
        assertThat(u.getSuscripcionType().getCodigo()).isEqualTo("DT_FREE");

        confirmar("profe@example.com");
        login("profe", "clave-segura").andExpect(jsonPath("$.inicio").value("/dt.html"));
        mvc.perform(get("/api/sesion").with(user("profe").authorities(() -> "DTFPP")))
                .andExpect(jsonPath("$.suscripcion.partidosPorMes").value(5));

        // El mensual (pago) todavía no se puede elegir: el DT empieza con el free.
        Map<String, Object> mensual = dt("dt.mensual");
        mensual.put("suscripcion", "DT_MENSUAL");
        register(mensual).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("El plan DT mensual estará disponible próximamente. Por ahora, "
                        + "empezá con el plan DT free."));

        // DT de la AMP.
        Map<String, Object> amp = dt("profe.amp");
        amp.putAll(Map.of("perfil", "DTAMP", "clubId", 3));
        register(amp).andExpect(status().isCreated());
        assertThat(usuarios.findByUsuario("profe.amp").orElseThrow().federacion()).isEqualTo(Federacion.AMP);

        Map<String, Object> planDeClub = dt("dt.d");
        planDeClub.put("suscripcion", "CLUB_MENSUAL");
        register(planDeClub).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("El plan Club mensual es para clubes."));
    }

    @Test
    void laPantallaDelDtEsSoloParaDt() throws Exception {
        mvc.perform(get("/dt.html").with(user("a").authorities(() -> "DTAMP"))).andExpect(status().isOk());
        mvc.perform(get("/dt.html").with(user("a").authorities(() -> "CLUBFPP"))).andExpect(status().is3xxRedirection());
        mvc.perform(get("/dt.html").with(user("a").authorities(() -> "PLAYERFPP"))).andExpect(status().is3xxRedirection());
        mvc.perform(get("/rinkreports/club.html").with(user("a").authorities(() -> "DTFPP"))).andExpect(status().is3xxRedirection());
        mvc.perform(get("/rinkreports/jugador.html").with(user("a").authorities(() -> "DTFPP"))).andExpect(status().is3xxRedirection());
        mvc.perform(get("/api/dt/partidos").with(user("a").authorities(() -> "CLUBFPP"))).andExpect(status().isForbidden());
        // El service worker de la planilla (para abrirla sin señal) se baja sin sesión, como el resto de los estáticos.
        mvc.perform(get("/dt-sw.js")).andExpect(status().isOk());
        mvc.perform(get("/js/dt/main.js")).andExpect(status().isOk());
    }

    @Test
    void planesDelDt() throws Exception {
        // El free primero (del más barato al más caro).
        mvc.perform(get("/api/suscripciones").param("perfil", "DTFPP")).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].codigo").value(org.hamcrest.Matchers.contains("DT_FREE", "DT_MENSUAL")))
                .andExpect(jsonPath("$[0].precio").value(0))
                .andExpect(jsonPath("$[0].equiposMax").value(1))
                .andExpect(jsonPath("$[0].partidosPorMes").value(5))
                .andExpect(jsonPath("$[0].vigenteHasta").value("2026-12-31"))
                .andExpect(jsonPath("$[1].vigenteHasta").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$[0].disponible").value(true))
                .andExpect(jsonPath("$[1].disponible").value(false))
                .andExpect(jsonPath("$[1].equiposMax").value(3))
                .andExpect(jsonPath("$[1].partidosPorMes").value(25))
                // USD 12, en pesos al dólar de respaldo de los tests ($ 1.500).
                .andExpect(jsonPath("$[1].precioUsd").value(12.0))
                .andExpect(jsonPath("$[1].precio").value(18000));
    }

    @Test
    void elPlanFreeDelDtVenceAFinDe2026() throws Exception {
        Map<String, Object> free = dt("dt.free");
        free.put("suscripcion", "DT_FREE");
        register(free).andExpect(status().isCreated());
        Usuario u = usuarios.findByUsuario("dt.free").orElseThrow();
        assertThat(u.getSuscripcionType().getCodigo()).isEqualTo("DT_FREE");
        // Vence el 31/12/2026, no al mes.
        assertThat(u.getSuscripcionHasta()).isEqualTo(java.time.LocalDate.of(2026, 12, 31));
    }

    @Test
    void soloSeRegistranLosPerfilesOfrecidos() throws Exception {
        Map<String, Object> otro = club("pedro");
        otro.put("perfil", "CLUBFSP");
        register(otro).andExpect(status().isBadRequest());

        Map<String, Object> clave = club("pedro");
        clave.put("clave", "corta");
        register(clave).andExpect(status().isBadRequest());

        Map<String, Object> sinPlan = club("pedro");
        sinPlan.remove("suscripcion");
        register(sinPlan).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Elegí un plan de suscripción."));

        Map<String, Object> planDeJugador = club("pedro");
        planDeJugador.put("suscripcion", "PLAYER_MENSUAL");
        register(planDeJugador).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("El plan Jugador mensual es para jugadores."));

        Map<String, Object> clubInexistente = club("pedro");
        clubInexistente.put("clubId", 99);
        register(clubInexistente).andExpect(status().isBadRequest());
        assertThat(usuarios.count()).isZero();
    }

    @Test
    void elEnlaceDeConfirmacionSirveUnaSolaVez() throws Exception {
        register(club("ana")).andExpect(status().isCreated());
        String token = token("ana@example.com", "/api/verificar");
        // La cuenta de club es de RinkReports: al ingreso de RinkReports.
        mvc.perform(get("/api/verificar").param("token", token)).andExpect(redirectedUrl("/rinkreports/login.html?verificado"));
        mvc.perform(get("/api/verificar").param("token", token)).andExpect(redirectedUrl("/login.html?verificacion=invalida"));
        mvc.perform(get("/api/verificar").param("token", "inventado")).andExpect(redirectedUrl("/login.html?verificacion=invalida"));
    }

    @Test
    void reenviarLaConfirmacionPorUsuarioOEmail() throws Exception {
        register(club("ana")).andExpect(status().isCreated());
        // Un mail por minuto como mucho: el reenvío inmediato no manda otro.
        mvc.perform(post("/api/verificacion/reenviar").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"usuario\":\"ana\"}")).andExpect(status().isOk());
        verify(mailer, times(1)).send(org.mockito.ArgumentMatchers.argThat(m -> m.to().equals("ana@example.com")));
        // Email inexistente: misma respuesta, sin mail.
        mvc.perform(post("/api/verificacion/reenviar").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"nadie@example.com\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.startsWith("Si hay una cuenta")));
        verify(mailer, never()).send(org.mockito.ArgumentMatchers.argThat(m -> m.to().equals("nadie@example.com")));
    }

    @Test
    void recuperarLaClave() throws Exception {
        register(club("ana")).andExpect(status().isCreated());
        confirmar("ana@example.com");

        String olvido = "{\"email\":\"ANA@example.com\"}";
        mvc.perform(post("/api/clave/olvido").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(olvido))
                .andExpect(status().isOk());
        String token = token("ana@example.com", "/restablecer.html");

        String corta = "{\"token\":\"%s\",\"clave\":\"corta\"}".formatted(token);
        mvc.perform(post("/api/clave/restablecer").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(corta))
                .andExpect(status().isBadRequest());
        String nueva = "{\"token\":\"%s\",\"clave\":\"otra-clave-segura\"}".formatted(token);
        mvc.perform(post("/api/clave/restablecer").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(nueva))
                .andExpect(status().isOk());

        login("ana", "clave-segura").andExpect(status().isUnauthorized());
        login("ana", "otra-clave-segura").andExpect(status().isOk());
        // El enlace no se puede volver a usar.
        mvc.perform(post("/api/clave/restablecer").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(nueva))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.startsWith("El enlace venció o ya se usó")));

        // Email inexistente: misma respuesta y ningún mail.
        mvc.perform(post("/api/clave/olvido").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"nadie@example.com\"}")).andExpect(status().isOk());
        verify(mailer, never()).send(org.mockito.ArgumentMatchers.argThat(m -> m.to().equals("nadie@example.com")));
    }

    @Test
    void cadaProductoTieneSuIngreso() throws Exception {
        for (Usuario u : List.of(saved("club.uno", Perfil.CLUBFPP), saved("dt.uno", Perfil.DTFPP))) {
            u.verificarEmail();
            usuarios.save(u);
        }
        assertThat(usuarios.findByUsuario("club.uno").orElseThrow().getProducto()).isEqualTo(Producto.REPORTS);
        assertThat(usuarios.findByUsuario("dt.uno").orElseThrow().getProducto()).isEqualTo(Producto.METRICS);
        // Desde su producto, entra; desde el otro, no (ni queda la sesión), y la respuesta dice dónde ingresar.
        loginEn("club.uno", "REPORTS").andExpect(status().isOk());
        loginEn("dt.uno", "METRICS").andExpect(status().isOk());
        loginEn("dt.uno", "REPORTS").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("OTRO_PRODUCTO"))
                .andExpect(jsonPath("$.ingreso").value("/login.html"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("RinkMetrics")));
        loginEn("club.uno", "METRICS").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.ingreso").value("/rinkreports/login.html"));
        // La sesión dice de qué producto es la cuenta.
        mvc.perform(get("/api/sesion").with(user("club.uno").authorities(() -> "CLUBFPP")))
                .andExpect(jsonPath("$.producto").value("REPORTS"));
    }

    @Test
    void elMismoEmailPuedeTenerUnaCuentaEnCadaProducto() throws Exception {
        Map<String, Object> dt = club("profe");
        dt.putAll(Map.of("perfil", "DTFPP", "suscripcion", "DT_FREE"));
        register(dt).andExpect(status().isCreated());
        // Otro DT con el mismo email: no (mismo producto).
        Map<String, Object> otroDt = new HashMap<>(dt);
        otroDt.put("usuario", "profe2");
        register(otroDt).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Ya hay una cuenta de RinkMetrics con ese email."));
        // Un jugador de RinkReports con el mismo email: sí. El usuario sigue siendo único en toda la web.
        Map<String, Object> jugador = player("profe.jugador");
        jugador.put("email", "profe@example.com");
        register(jugador).andExpect(status().isCreated());
        Map<String, Object> mismoUsuario = player("profe");
        mismoUsuario.put("email", "otro@example.com");
        register(mismoUsuario).andExpect(status().isConflict());

        // Cada mail, con la marca de su producto.
        ArgumentCaptor<MailContent> mails = ArgumentCaptor.forClass(MailContent.class);
        verify(mailer, atLeastOnce()).send(mails.capture());
        assertThat(mails.getAllValues()).extracting(MailContent::producto, MailContent::subject)
                .contains(org.assertj.core.groups.Tuple.tuple(Producto.METRICS, "Confirmá tu cuenta de RinkMetrics"),
                        org.assertj.core.groups.Tuple.tuple(Producto.REPORTS, "Confirmá tu cuenta de RinkReports"));

        // "Olvidé mi clave" desde RinkReports va a la cuenta de RinkReports, con su enlace.
        confirmar("profe@example.com");
        mvc.perform(post("/api/clave/olvido").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"profe@example.com\",\"producto\":\"REPORTS\"}")).andExpect(status().isOk());
        verify(mailer, atLeastOnce()).send(mails.capture());
        MailContent recupero = mails.getAllValues().stream().filter(m -> m.subject().startsWith("Elegí una clave"))
                .reduce((a, b) -> b).orElseThrow();
        assertThat(recupero.producto()).isEqualTo(Producto.REPORTS);
        assertThat(recupero.url()).contains("/restablecer.html?producto=reports&token=");
        assertThat(realMailer.html(recupero)).contains("RinkReports").doesNotContain(">RinkMetrics<");
    }

    @Test
    void lasOpcionesDelRegistro() throws Exception {
        mvc.perform(get("/api/registro/opciones")).andExpect(status().isOk())
                .andExpect(jsonPath("$.club").isBoolean()).andExpect(jsonPath("$.jugadorAmp").isBoolean());
    }

    @Test
    void elMailTieneElLookDeLaWeb() {
        MailContent m = new MailContent("ana@example.com", "Confirmá tu cuenta de RinkMetrics", "Un paso más",
                "Tu cuenta", "Confirmá tu email", "ana", List.of("Gracias por sumarte."), "Confirmar mi email",
                "http://localhost/api/verificar?token=abc", "El enlace vale 48 horas.");
        String html = realMailer.html(m);
        assertThat(html).contains("#e6ecef", "#0a7ea4", "cid:logo", "Confirmá tu email", "Hola ana:",
                "href=\"http://localhost/api/verificar?token=abc\"", "Confirmar mi email ►", "RinkMetrics");
        assertThat(m.text()).contains("Hola ana:", "Confirmar mi email: http://localhost/api/verificar?token=abc");
        assertThat(new String(Mailer.logo(), 1, 3)).isEqualTo("PNG");
    }

    @Test
    void vistaPreviaDelMail() throws Exception {
        // Copia para revisar a ojo en el navegador: target/mail-ejemplo.html (logo embebido en lugar de cid:).
        MailContent m = new MailContent("ana@example.com", "Confirmá tu cuenta de RinkMetrics", "Un paso más",
                "Tu cuenta", "Confirmá tu email", "ana",
                List.of("Gracias por sumarte a RinkMetrics. Para activar tu cuenta (usuario ana), confirmá tu email con el botón de abajo.",
                        "Después ya podés ingresar y generar tus informes de hockey sobre patines."),
                "Confirmar mi email", "http://localhost:8091/api/verificar?token=ejemplo",
                "El enlace vale 48 horas. Si no creaste esta cuenta, ignorá este mail.");
        String preview = realMailer.html(m).replace("cid:logo",
                "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(Mailer.logo()));
        java.nio.file.Files.writeString(java.nio.file.Path.of("target", "mail-ejemplo.html"), preview);
    }

    @Test
    void planesDeClubYDeJugador() throws Exception {
        // El club: el free (hasta el 31/12/2026, sin límite de informes) y el mensual, que todavía no se vende:
        // "Próximamente", con precio a definir.
        mvc.perform(get("/api/suscripciones").param("perfil", "CLUBFPP")).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].codigo").value(org.hamcrest.Matchers.contains("CLUB_FREE", "CLUB_MENSUAL")))
                .andExpect(jsonPath("$[0].precio").value(0))
                .andExpect(jsonPath("$[0].informesPorMes").value(0))
                .andExpect(jsonPath("$[0].vigenteHasta").value("2026-12-31"))
                .andExpect(jsonPath("$[0].disponible").value(true))
                .andExpect(jsonPath("$[1].precioUsd").value(20.0))
                // En pesos al dólar de respaldo de los tests ($ 1.500), redondeado hacia abajo a centenas.
                .andExpect(jsonPath("$[1].precio").value(30000))
                .andExpect(jsonPath("$[1].dolar").value(1500))
                .andExpect(jsonPath("$[1].disponible").value(false))
                .andExpect(jsonPath("$[1].precioADefinir").value(true));
        Map<String, Object> pago = club("ana.pago");
        pago.put("suscripcion", "CLUB_MENSUAL");
        register(pago).andExpect(status().isBadRequest());
        // El jugador: el free (hasta el 31/12/2026) y el mensual.
        mvc.perform(get("/api/suscripciones").param("perfil", "PLAYERFPP")).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].codigo").value(org.hamcrest.Matchers.contains("PLAYER_FREE", "PLAYER_MENSUAL")))
                .andExpect(jsonPath("$[0].precio").value(0))
                .andExpect(jsonPath("$[0].vigenteHasta").value("2026-12-31"))
                .andExpect(jsonPath("$[0].informesPorMes").value(5))
                .andExpect(jsonPath("$[1].precioUsd").value(7.0))
                .andExpect(jsonPath("$[1].precio").value(10500))
                .andExpect(jsonPath("$[1].informesPorMes").value(4))
                .andExpect(jsonPath("$[1].vigenteHasta").value(org.hamcrest.Matchers.nullValue()));

        // Solo existen los planes mensuales.
        Map<String, Object> otro = club("ana.otro");
        otro.put("suscripcion", "CLUB_ANUAL");
        register(otro).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Plan de suscripción inválido: CLUB_ANUAL."));
    }

    @Test
    void sinTokenCsrfNoSeAceptanPost() throws Exception {
        mvc.perform(post("/api/registro").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(club("ana")))).andExpect(status().isForbidden());
        assertThat(usuarios.count()).isZero();
    }

    @Test
    void elClubSoloPideInformesDeSuClub() throws Exception {
        saved("club", Perfil.CLUBFPP);
        ReportService.Job job = mock(ReportService.Job.class);
        when(job.id()).thenReturn(UUID.randomUUID());
        when(job.status()).thenReturn(ReportService.Status.PENDIENTE);
        when(reports.submit(any(), eq("club"))).thenReturn(job);
        String pedido = "{\"clubId\":%d,\"categoria\":\"PRIMERA\",\"rama\":\"MASCULINO\"}";

        mvc.perform(post("/api/informes").with(user("club").authorities(() -> "CLUBFPP")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(pedido.formatted(99)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Solo podés pedir informes de tu club (VELEZ)."));
        assertThat(usuarios.findByUsuario("club").orElseThrow().getInformesPedidos()).isZero();   // el rechazado no cuenta
        mvc.perform(post("/api/informes").with(user("club").authorities(() -> "CLUBFPP")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(pedido.formatted(74)))
                .andExpect(status().isAccepted());
        mvc.perform(post("/api/informes").with(user("club").authorities(() -> "CLUBFPP")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(pedido.formatted(74)))
                .andExpect(status().isAccepted());
        assertThat(usuarios.findByUsuario("club").orElseThrow().getInformesPedidos()).isEqualTo(2);
    }

    @Test
    void elJugadorTambienSumaInformesPedidos() throws Exception {
        Usuario u = saved("jug", Perfil.PLAYERFPP);
        u.vincularJugador(3851, "Ignacio J Graiño", "PRIMERA", "MASCULINO");
        usuarios.save(u);
        com.madsports.player.PlayerReportService.Job job = mock(com.madsports.player.PlayerReportService.Job.class);
        when(job.id()).thenReturn(UUID.randomUUID());
        when(job.status()).thenReturn(ReportService.Status.PENDIENTE);
        when(playerReports.submit(any(), eq("jug"))).thenReturn(job);

        mvc.perform(post("/api/jugador/informes").with(user("jug").authorities(() -> "PLAYERFPP")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"anio\":2026,\"periodo\":\"TEMPORADA\"}"))
                .andExpect(status().isAccepted());
        assertThat(usuarios.findByUsuario("jug").orElseThrow().getInformesPedidos()).isEqualTo(1);
        // Los informes de ejemplo no cuentan.
        mvc.perform(get("/api/ejemplo-jugador.pdf").with(user("jug").authorities(() -> "PLAYERFPP")))
                .andExpect(status().isOk());
        assertThat(usuarios.findByUsuario("jug").orElseThrow().getInformesPedidos()).isEqualTo(1);
    }

    @Test
    void elJugadorFreePideHasta5InformesPorMes() throws Exception {
        Usuario u = saved("cupo", Perfil.PLAYERFPP);
        u.vincularJugador(3851, "Ignacio J Graiño", "PRIMERA", "MASCULINO");
        u.suscribir(suscripciones.findByCodigo("PLAYER_FREE").orElseThrow(), java.time.LocalDate.now());
        usuarios.save(u);
        com.madsports.player.PlayerReportService.Job job = mock(com.madsports.player.PlayerReportService.Job.class);
        when(job.id()).thenReturn(UUID.randomUUID());
        when(job.status()).thenReturn(ReportService.Status.PENDIENTE);
        when(playerReports.submit(any(), eq("cupo"))).thenReturn(job);

        for (int i = 1; i <= 5; i++) {
            pedirInforme("cupo").andExpect(status().isAccepted());
        }
        // El sexto del mes, no: 429 con el día en que se renueva. No cuenta ni se pide.
        pedirInforme("cupo").andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.startsWith(
                        "Ya pediste los 5 informes de este mes. El cupo se renueva el 1/")));
        verify(playerReports, times(5)).submit(any(), eq("cupo"));
        assertThat(usuarios.findByUsuario("cupo").orElseThrow().getInformesPedidos()).isEqualTo(5);
        mvc.perform(get("/api/sesion").with(user("cupo").authorities(() -> "PLAYERFPP")))
                .andExpect(jsonPath("$.informesMes.usados").value(5))
                .andExpect(jsonPath("$.informesMes.limite").value(5));
    }

    @Test
    void unPedidoInvalidoNoGastaElCupo() throws Exception {
        Usuario u = saved("cupo2", Perfil.PLAYERFPP);
        u.vincularJugador(3851, "Ignacio J Graiño", "PRIMERA", "MASCULINO");
        u.suscribir(suscripciones.findByCodigo("PLAYER_FREE").orElseThrow(), java.time.LocalDate.now());
        usuarios.save(u);
        when(playerReports.submit(any(), eq("cupo2"))).thenThrow(new IllegalArgumentException("Período inválido."));
        pedirInforme("cupo2").andExpect(status().isBadRequest());
        mvc.perform(get("/api/sesion").with(user("cupo2").authorities(() -> "PLAYERFPP")))
                .andExpect(jsonPath("$.informesMes.usados").value(0));
    }

    ResultActions pedirInforme(String usuario) throws Exception {
        return mvc.perform(post("/api/jugador/informes").with(user(usuario).authorities(() -> "PLAYERFPP")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"anio\":2026,\"periodo\":\"TEMPORADA\"}"));
    }

    @Test
    void tiposDeSuscripcionYVigencia() throws Exception {
        // Un plan mensual y uno free por tipo de usuario.
        assertThat(suscripciones.findAll()).extracting(SuscripcionType::getCodigo, SuscripcionType::getTipoUsuario)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("PLAYER_FREE", "PLAYER"),
                        org.assertj.core.groups.Tuple.tuple("PLAYER_MENSUAL", "PLAYER"),
                        org.assertj.core.groups.Tuple.tuple("CLUB_FREE", "CLUB"),
                        org.assertj.core.groups.Tuple.tuple("CLUB_MENSUAL", "CLUB"),
                        org.assertj.core.groups.Tuple.tuple("DT_FREE", "DT"),
                        org.assertj.core.groups.Tuple.tuple("DT_MENSUAL", "DT"));

        java.time.LocalDate desde = java.time.LocalDate.of(2026, 10, 15);
        assertThat(suscripciones.findByCodigo("PLAYER_MENSUAL").orElseThrow().hasta(desde)).isEqualTo("2026-11-14");

        Usuario u = saved("club", Perfil.CLUBFPP);
        // A un club no se le puede asignar una suscripción de jugador.
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        u.suscribir(suscripciones.findByCodigo("PLAYER_MENSUAL").orElseThrow(), desde))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("es para jugadores");
        u.suscribir(suscripciones.findByCodigo("CLUB_MENSUAL").orElseThrow(), desde);
        usuarios.save(u);
        Usuario leido = usuarios.findByUsuario("club").orElseThrow();
        assertThat(leido.getSuscripcionType().getNombre()).isEqualTo("Club mensual");
        assertThat(leido.getSuscripcionDesde()).isEqualTo("2026-10-15");
        assertThat(leido.getSuscripcionHasta()).isEqualTo("2026-11-14");
        mvc.perform(get("/api/sesion").with(user("club").authorities(() -> "CLUBFPP")))
                .andExpect(jsonPath("$.suscripcion.tipo").value("CLUB_MENSUAL"))
                .andExpect(jsonPath("$.suscripcion.tipoUsuario").value("CLUB"))
                .andExpect(jsonPath("$.suscripcion.hasta").value("2026-11-14"))
                .andExpect(jsonPath("$.informesPedidos").value(0));
    }

    @Test
    void cadaPerfilVaASuPantalla() throws Exception {
        mvc.perform(get("/rinkreports/club.html").with(user("j").authorities(() -> "PLAYERFPP"))).andExpect(redirectedUrl("/rinkreports/jugador.html"));
        mvc.perform(get("/api/informes/" + UUID.randomUUID()).with(user("j").authorities(() -> "PLAYERFPP")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/rinkreports/jugador.html").with(user("c").authorities(() -> "CLUBFPP"))).andExpect(redirectedUrl("/rinkreports/club.html"));
        mvc.perform(get("/api/jugador/informes/" + UUID.randomUUID()).with(user("c").authorities(() -> "CLUBFPP")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/rinkreports/jugador.html").with(user("j").authorities(() -> "PLAYERFPP"))).andExpect(status().isOk());
    }

    @Test
    void laSesionTraeLosDatosDelUsuario() throws Exception {
        saved("club", Perfil.CLUBFPP);
        mvc.perform(get("/api/sesion").with(user("club").authorities(() -> "CLUBFPP")))
                .andExpect(jsonPath("$.autenticado").value(true))
                .andExpect(jsonPath("$.perfiles[0]").value("CLUBFPP"))
                .andExpect(jsonPath("$.club.id").value(74))
                .andExpect(jsonPath("$.club.nombre").value("VELEZ"))
                .andExpect(jsonPath("$.inicio").value("/rinkreports/club.html"));
    }
}
