package com.madsports.account;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.madsports.catalog.CatalogService;
import com.madsports.catalog.CatalogService.ClubView;
import com.madsports.config.WebConfig;
import com.madsports.sidgad.model.Federation;
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
    @Autowired UserAccountRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired Environment env;

    @MockitoBean CatalogService catalog;
    @MockitoBean RosterService roster;
    @MockitoBean ReportService reports;
    @MockitoBean com.madsports.player.PlayerReportService playerReports;
    @Autowired SubscriptionPlanRepository subscriptions;
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
        users.deleteAll();
        when(catalog.defaultYear()).thenReturn(2026);
        when(catalog.checkYear(any())).thenReturn(2026);
        when(catalog.club(Federation.FPP, 74, 2026)).thenReturn(VELEZ);
        when(catalog.club(eq(Federation.FPP), eq(99), eq(2026)))
                .thenThrow(new IllegalArgumentException("El club 99 no juega en la FPP en 2026."));
        when(roster.find(Federation.FPP, 74, 2026, "PRIMERA", "MASCULINO", 3851))
                .thenReturn(Optional.of(new RosterPlayer(3851, "Ignacio J Graiño", "Ignacio J", "Graiño",
                        "Primera Masculino")));
        // AMP (Mendoza): Leonardo Murialdo; Vélez no juega en la AMP.
        when(catalog.club(Federation.AMP, 3, 2026)).thenReturn(MURIALDO);
        when(catalog.club(eq(Federation.AMP), eq(74), eq(2026)))
                .thenThrow(new IllegalArgumentException("El club 74 no juega en la AMP en 2026."));
        when(roster.find(Federation.AMP, 3, 2026, "JUVENIL", "MASCULINO", 6188))
                .thenReturn(Optional.of(new RosterPlayer(6188, "Victor Daniel Terraza Gomez", "Victor Daniel",
                        "Terraza Gomez", "Juvenil Masculino")));
    }

    Map<String, Object> club(String username) {
        Map<String, Object> m = new HashMap<>(Map.of("profile", "CLUBFPP", "username", username, "password", "clave-segura",
                "email", username + "@example.com", "clubId", 74, "subscription", "CLUB_FREE"));
        return m;
    }

    /** El jugador no manda nombre ni apellido: se toman del jugador elegido. */
    Map<String, Object> player(String username) {
        Map<String, Object> m = club(username);
        m.putAll(Map.of("profile", "PLAYERFPP", "category", "PRIMERA", "branch", "MASCULINO",
                "playerId", 3851, "subscription", "PLAYER_MONTHLY"));
        return m;
    }

    ResultActions register(Map<String, Object> body) throws Exception {
        return mvc.perform(post("/api/signup").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    ResultActions login(String username, String password) throws Exception {
        return mvc.perform(post("/api/login").with(csrf()).param("username", username).param("password", password));
    }

    /** Ingreso desde la pantalla de un producto (REPORTS o METRICS). */
    ResultActions loginTo(String username, String product) throws Exception {
        return mvc.perform(post("/api/login").with(csrf()).param("username", username).param("password", "clave-segura")
                .param("product", product));
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
    void confirm(String email) throws Exception {
        mvc.perform(get("/api/verify").param("token", token(email, "/api/verify")))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", org.hamcrest.Matchers.endsWith("login.html?verified")));
    }

    UserAccount saved(String username, Profile profile) {
        return users.save(new UserAccount(username, encoder.encode("clave-segura"), username + "@example.com", "Ana",
                "Pérez", 74, "VELEZ", "CAVS", EnumSet.of(profile), Instant.now()));
    }

    /** Ingreso con "Mantener la sesión iniciada": devuelve la respuesta (con la cookie RECORDARME y la sesión). */
    MvcResult loginRemembering(String username) throws Exception {
        return mvc.perform(post("/api/login").with(csrf()).param("username", username).param("password", "clave-segura")
                .param("rememberMe", "true")).andExpect(status().isOk()).andReturn();
    }

    int remembered(String username) {
        // "Recordarme" guarda el nombre de la sesión: producto y usuario ("REPORTS:ana").
        return jdbc.queryForObject("select count(*) from persistent_logins where username like ?", Integer.class,
                "%:" + username);
    }

    @Test
    void publicHealthCheckWithoutDetails() throws Exception {
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
    void rememberMeLogsBackInEvenIfTheSessionIsLost() throws Exception {
        register(club("ana")).andExpect(status().isCreated());
        confirm("ana@example.com");
        Cookie c = loginRemembering("ana").getResponse().getCookie("REMEMBER_ME");
        assertThat(c).isNotNull();
        assertThat(c.getMaxAge()).isEqualTo(30 * 24 * 3600);
        assertThat(c.isHttpOnly()).isTrue();
        assertThat(remembered("ana")).isEqualTo(1);

        // Sin sesión (venció, se cerró el navegador o se reinició la app): la cookie vuelve a entrar sola.
        MvcResult r = mvc.perform(get("/api/session").cookie(c))
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.username").value("ana")).andReturn();
        // El token cambia en cada uso; con el nuevo, también las pantallas y la API del perfil.
        Cookie fresh = r.getResponse().getCookie("REMEMBER_ME");
        assertThat(fresh.getValue()).isNotEqualTo(c.getValue());
        mvc.perform(get("/rinkreports/club.html").cookie(fresh)).andExpect(status().isOk());

        // Sin marcar la casilla no queda recordado.
        register(club("beto")).andExpect(status().isCreated());
        confirm("beto@example.com");
        assertThat(login("beto", "clave-segura").andExpect(status().isOk()).andReturn().getResponse()
                .getCookie("REMEMBER_ME")).isNull();
        assertThat(remembered("beto")).isZero();
    }

    @Test
    void logoutAndPasswordChangeStopRemembering() throws Exception {
        register(club("ana")).andExpect(status().isCreated());
        confirm("ana@example.com");

        // Salir borra el dispositivo recordado.
        MvcResult r = loginRemembering("ana");
        Cookie c = r.getResponse().getCookie("REMEMBER_ME");
        mvc.perform(post("/api/logout").with(csrf()).session((MockHttpSession) r.getRequest().getSession()).cookie(c))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getCookie("REMEMBER_ME").getMaxAge()).isZero());
        assertThat(remembered("ana")).isZero();
        mvc.perform(get("/api/session").cookie(c)).andExpect(jsonPath("$.authenticated").value(false));

        // Cambiar la clave también: hay que volver a ingresar con la nueva en cada dispositivo.
        Cookie other = loginRemembering("ana").getResponse().getCookie("REMEMBER_ME");
        mvc.perform(post("/api/password/forgot").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"ana@example.com\"}")).andExpect(status().isOk());
        String token = token("ana@example.com", "/reset-password.html");
        mvc.perform(post("/api/password/reset").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"%s\",\"password\":\"otra-clave-segura\"}".formatted(token)))
                .andExpect(status().isOk());
        assertThat(remembered("ana")).isZero();
        mvc.perform(get("/api/session").cookie(other)).andExpect(jsonPath("$.authenticated").value(false));
    }

    @Test
    void withoutConfirmedEmailItIsNotRememberedEither() throws Exception {
        register(club("ana")).andExpect(status().isCreated());
        MvcResult r = mvc.perform(post("/api/login").with(csrf()).param("username", "ana").param("password", "clave-segura")
                        .param("rememberMe", "true"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED")).andReturn();
        // Si el ingreso llegó a crear la cookie, la respuesta la borra después (el navegador aplica la última).
        Cookie last = java.util.Arrays.stream(r.getResponse().getCookies())
                .filter(x -> x.getName().equals("REMEMBER_ME")).reduce((a, b) -> b).orElse(null);
        assertThat(last == null || last.getMaxAge() == 0).isTrue();
        assertThat(remembered("ana")).isZero();
    }

    @Test
    void theSessionLasts8HoursWithoutActivity() {
        assertThat(env.getProperty("server.servlet.session.timeout")).isEqualTo("8h");
    }

    @Test
    void withoutSessionPagesGoToLoginAndApiAnswers401() throws Exception {
        // La home es pública (con los informes de ejemplo); la pantalla de club pide sesión.
        mvc.perform(get("/")).andExpect(status().isOk());
        mvc.perform(get("/api/sample.pdf")).andExpect(status().isOk());
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
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl("/rinkmetrics/index.html"));
        // Al ingreso, con la pantalla a la que volver después.
        // Las pantallas de RinkReports van a su ingreso; las de RinkMetrics, al suyo.
        mvc.perform(get("/rinkreports/club.html")).andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/rinkreports/login.html?returnTo=%2Frinkreports%2Fclub.html"));
        mvc.perform(get("/rinkreports/player.html"))
                .andExpect(redirectedUrl("/rinkreports/login.html?returnTo=%2Frinkreports%2Fplayer.html"));
        mvc.perform(get("/coach.html")).andExpect(redirectedUrl("/rinkmetrics/login.html?returnTo=%2Fcoach.html"));
        mvc.perform(get("/rinkreports/login.html")).andExpect(status().isOk());
        mvc.perform(get("/rinkreports/signup.html")).andExpect(status().isOk());
        // Las direcciones de antes llevan a las nuevas, sin sesión y con los mismos parámetros.
        mvc.perform(get("/club.html")).andExpect(redirectedUrl("/rinkreports/club.html"));
        mvc.perform(get("/jugador.html")).andExpect(redirectedUrl("/rinkreports/player.html"));
        mvc.perform(get("/informes.html")).andExpect(redirectedUrl("/rinkreports"));
        mvc.perform(get("/dt.html")).andExpect(redirectedUrl("/coach.html"));
        mvc.perform(get("/login.html?verified")).andExpect(redirectedUrl("/rinkmetrics/login.html?verified"));
        mvc.perform(get("/restablecer.html?token=abc"))
                .andExpect(redirectedUrl("/reset-password.html?token=abc"));
        for (Map.Entry<String, String> e : WebConfig.OLD_PAGES.entrySet()) {
            mvc.perform(get(e.getKey())).andExpect(redirectedUrl(e.getValue()));
        }
        // Los ejemplos de RinkReports, públicos: en pantalla, PDF y Excel.
        mvc.perform(get("/rinkreports/sample.html")).andExpect(status().isOk());
        mvc.perform(get("/rinkmetrics/login.html")).andExpect(status().isOk());
        // Las presentaciones de las dos secciones (Informes y DT) se ven sin sesión.
        mvc.perform(get("/rinkmetrics/index.html")).andExpect(status().isOk());
        // La demo de RinkMetrics y sus capturas, también. La demo es la misma página que /coach.html (sin copia).
        mvc.perform(get("/coach-demo.html")).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/js/coach/main.js")));
        mvc.perform(get("/rinkmetrics/why.html")).andExpect(status().isOk());
        mvc.perform(get("/img/demo/carga-web.png")).andExpect(status().isOk());
        mvc.perform(get("/rinkmetrics/signup.html")).andExpect(status().isOk());
        // El service worker de antes, para que se borre solo.
        mvc.perform(get("/dt-sw.js")).andExpect(status().isOk());
        mvc.perform(get("/api/sample-player.pdf")).andExpect(status().isOk());
        mvc.perform(get("/api/reports/" + UUID.randomUUID())).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/session")).andExpect(status().isOk()).andExpect(jsonPath("$.authenticated").value(false));
    }

    @Test
    void clubSignupAndLogin() throws Exception {
        register(club("Ana.Club")).andExpect(status().isCreated()).andExpect(jsonPath("$.username").value("ana.club"));
        register(club("ana.club")).andExpect(status().isConflict());

        UserAccount u = users.findByUsername("ana.club").orElseThrow();
        assertThat(u.getProfiles()).containsExactly(Profile.CLUBFPP);
        assertThat(u.getPassword()).isNotEqualTo("clave-segura").startsWith("$2");
        assertThat(u.getClubName()).isEqualTo("VELEZ");
        // El plan elegido queda como suscripción desde hoy.
        assertThat(u.getSubscriptionPlan().getCode()).isEqualTo("CLUB_FREE");
        assertThat(u.getSubscriptionFrom()).isEqualTo(java.time.LocalDate.now());
        assertThat(u.getSubscriptionTo()).isEqualTo("2026-12-31");
        assertThat(u.getName()).isNull();   // el registro no pide nombre ni apellido

        // Sin confirmar el email no se ingresa (con la clave correcta se avisa por qué).
        login("ana.club", "clave-segura").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"));
        confirm("Ana.Club@example.com");
        assertThat(users.findByUsername("ana.club").orElseThrow().isEmailVerified()).isTrue();
        login("ANA.CLUB", "clave-segura").andExpect(status().isOk()).andExpect(jsonPath("$.home").value("/rinkreports/club.html"));
        login("ana.club", "otra-clave").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Usuario o clave incorrectos."));
    }

    @Test
    void playerSignupWithTheFppPlayer() throws Exception {
        Map<String, Object> withoutPlayer = player("juan");
        withoutPlayer.remove("playerId");
        register(withoutPlayer).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Elegí tu nombre en la lista de jugadores del club."));

        Map<String, Object> otherPlayer = player("juan");
        otherPlayer.put("playerId", 1);
        register(otherPlayer).andExpect(status().isBadRequest());

        register(player("juan")).andExpect(status().isCreated());
        UserAccount u = users.findByUsername("juan").orElseThrow();
        assertThat(u.getProfiles()).containsExactly(Profile.PLAYERFPP);
        assertThat(u.getPlayerId()).isEqualTo(3851);
        assertThat(u.getPlayerName()).isEqualTo("Ignacio J Graiño");
        assertThat(u.getSubscriptionPlan().getCode()).isEqualTo("PLAYER_MONTHLY");
        assertThat(u.getName()).isEqualTo("Ignacio J");
        assertThat(u.getLastName()).isEqualTo("Graiño");
        assertThat(u.getCategory()).isEqualTo("PRIMERA");
        assertThat(u.getBranch()).isEqualTo("MASCULINO");

        // Un jugador, un usuario.
        Map<String, Object> samePlayer = player("juan2");
        register(samePlayer).andExpect(status().isConflict());

        confirm("juan@example.com");
        login("juan", "clave-segura").andExpect(jsonPath("$.home").value("/rinkreports/player.html"));
    }

    @Test
    void signupOfAmpClubAndPlayer() throws Exception {
        Map<String, Object> clubAmp = club("murialdo");
        clubAmp.putAll(Map.of("profile", "CLUBAMP", "clubId", 3));
        register(clubAmp).andExpect(status().isCreated());
        UserAccount club = users.findByUsername("murialdo").orElseThrow();
        assertThat(club.getProfiles()).containsExactly(Profile.CLUBAMP);
        assertThat(club.federation()).isEqualTo(Federation.AMP);
        assertThat(club.getClubName()).isEqualTo("LEONARDO MURIALDO");

        // El club tiene que ser de la AMP: Vélez es de la FPP.
        Map<String, Object> velezInAmp = club("velez.amp");
        velezInAmp.put("profile", "CLUBAMP");
        register(velezInAmp).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("El club 74 no juega en la AMP en 2026."));

        Map<String, Object> playerAmp = player("terraza");
        playerAmp.putAll(Map.of("profile", "PLAYERAMP", "clubId", 3, "category", "JUVENIL", "playerId", 6188));
        register(playerAmp).andExpect(status().isCreated());
        UserAccount player = users.findByUsername("terraza").orElseThrow();
        assertThat(player.getProfiles()).containsExactly(Profile.PLAYERAMP);
        assertThat(player.getPlayerName()).isEqualTo("Victor Daniel Terraza Gomez");

        confirm("murialdo@example.com");
        login("murialdo", "clave-segura").andExpect(jsonPath("$.home").value("/rinkreports/club.html"));
        mvc.perform(get("/api/session").with(user("murialdo").authorities(() -> "CLUBAMP")))
                .andExpect(jsonPath("$.federation").value("AMP"));
        confirm("terraza@example.com");
        login("terraza", "clave-segura").andExpect(jsonPath("$.home").value("/rinkreports/player.html"));
    }

    @Test
    void screensAcceptProfilesOfBothFederations() throws Exception {
        mvc.perform(get("/rinkreports/club.html").with(user("a").authorities(() -> "CLUBAMP"))).andExpect(status().isOk());
        mvc.perform(get("/rinkreports/player.html").with(user("a").authorities(() -> "PLAYERAMP"))).andExpect(status().isOk());
        // Cada perfil, solo su pantalla.
        mvc.perform(get("/rinkreports/player.html").with(user("a").authorities(() -> "CLUBAMP")))
                .andExpect(status().is3xxRedirection());
        mvc.perform(post("/api/reports").with(user("a").authorities(() -> "PLAYERAMP")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    Map<String, Object> coach(String username) {
        Map<String, Object> m = club(username);
        m.putAll(Map.of("profile", "COACHFPP", "subscription", "COACH_FREE"));
        return m;
    }

    @Test
    void coachSignupWithoutTeams() throws Exception {
        // El DT ya no elige equipos al registrarse: los arma después en la sección Equipo.
        register(coach("profe")).andExpect(status().isCreated());
        UserAccount u = users.findByUsername("profe").orElseThrow();
        assertThat(u.getProfiles()).containsExactly(Profile.COACHFPP);
        assertThat(u.isCoach()).isTrue();
        assertThat(u.getSubscriptionPlan().getCode()).isEqualTo("COACH_FREE");

        confirm("profe@example.com");
        login("profe", "clave-segura").andExpect(jsonPath("$.home").value("/coach.html"));
        mvc.perform(get("/api/session").with(user("profe").authorities(() -> "COACHFPP")))
                .andExpect(jsonPath("$.subscription.matchesPerMonth").value(5));

        // El mensual (pago) todavía no se puede elegir: el DT empieza con el free.
        Map<String, Object> monthly = coach("dt.mensual");
        monthly.put("subscription", "COACH_MONTHLY");
        register(monthly).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("El plan DT mensual estará disponible próximamente. Por ahora, "
                        + "empezá con el plan DT free."));

        // DT de la AMP.
        Map<String, Object> amp = coach("profe.amp");
        amp.putAll(Map.of("profile", "COACHAMP", "clubId", 3));
        register(amp).andExpect(status().isCreated());
        assertThat(users.findByUsername("profe.amp").orElseThrow().federation()).isEqualTo(Federation.AMP);

        Map<String, Object> clubPlan = coach("dt.d");
        clubPlan.put("subscription", "CLUB_MONTHLY");
        register(clubPlan).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("El plan Club mensual es para clubes."));
    }

    @Test
    void theCoachScreenIsOnlyForCoaches() throws Exception {
        mvc.perform(get("/coach.html").with(user("a").authorities(() -> "COACHAMP"))).andExpect(status().isOk());
        mvc.perform(get("/coach.html").with(user("a").authorities(() -> "CLUBFPP"))).andExpect(status().is3xxRedirection());
        mvc.perform(get("/coach.html").with(user("a").authorities(() -> "PLAYERFPP"))).andExpect(status().is3xxRedirection());
        mvc.perform(get("/rinkreports/club.html").with(user("a").authorities(() -> "COACHFPP"))).andExpect(status().is3xxRedirection());
        mvc.perform(get("/rinkreports/player.html").with(user("a").authorities(() -> "COACHFPP"))).andExpect(status().is3xxRedirection());
        mvc.perform(get("/api/coach/matches").with(user("a").authorities(() -> "CLUBFPP"))).andExpect(status().isForbidden());
        // El service worker de la planilla (para abrirla sin señal) se baja sin sesión, como el resto de los estáticos.
        mvc.perform(get("/dt-sw.js")).andExpect(status().isOk());
        mvc.perform(get("/js/coach/main.js")).andExpect(status().isOk());
    }

    @Test
    void coachPlans() throws Exception {
        // El free primero (del más barato al más caro).
        mvc.perform(get("/api/plans").param("profile", "COACHFPP")).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].code").value(org.hamcrest.Matchers.contains("COACH_FREE", "COACH_MONTHLY")))
                .andExpect(jsonPath("$[0].price").value(0))
                .andExpect(jsonPath("$[0].maxTeams").value(1))
                .andExpect(jsonPath("$[0].matchesPerMonth").value(5))
                .andExpect(jsonPath("$[0].validUntil").value("2026-12-31"))
                .andExpect(jsonPath("$[1].validUntil").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$[0].available").value(true))
                .andExpect(jsonPath("$[1].available").value(false))
                .andExpect(jsonPath("$[1].maxTeams").value(3))
                .andExpect(jsonPath("$[1].matchesPerMonth").value(25))
                // USD 12, en pesos al dólar de respaldo de los tests ($ 1.500).
                .andExpect(jsonPath("$[1].priceUsd").value(12.0))
                .andExpect(jsonPath("$[1].price").value(18000));
    }

    @Test
    void theCoachFreePlanEndsAtEndOf2026() throws Exception {
        Map<String, Object> free = coach("dt.free");
        free.put("subscription", "COACH_FREE");
        register(free).andExpect(status().isCreated());
        UserAccount u = users.findByUsername("dt.free").orElseThrow();
        assertThat(u.getSubscriptionPlan().getCode()).isEqualTo("COACH_FREE");
        // Vence el 31/12/2026, no al mes.
        assertThat(u.getSubscriptionTo()).isEqualTo(java.time.LocalDate.of(2026, 12, 31));
    }

    @Test
    void onlyOfferedProfilesCanSignUp() throws Exception {
        Map<String, Object> other = club("pedro");
        other.put("profile", "CLUBFSP");
        register(other).andExpect(status().isBadRequest());

        Map<String, Object> password = club("pedro");
        password.put("password", "corta");
        register(password).andExpect(status().isBadRequest());

        Map<String, Object> withoutPlan = club("pedro");
        withoutPlan.remove("subscription");
        register(withoutPlan).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Elegí un plan de suscripción."));

        Map<String, Object> playerPlan = club("pedro");
        playerPlan.put("subscription", "PLAYER_MONTHLY");
        register(playerPlan).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("El plan Jugador mensual es para jugadores."));

        Map<String, Object> missingClub = club("pedro");
        missingClub.put("clubId", 99);
        register(missingClub).andExpect(status().isBadRequest());
        assertThat(users.count()).isZero();
    }

    @Test
    void theConfirmationLinkWorksOnlyOnce() throws Exception {
        register(club("ana")).andExpect(status().isCreated());
        String token = token("ana@example.com", "/api/verify");
        // La cuenta de club es de RinkReports: al ingreso de RinkReports.
        mvc.perform(get("/api/verify").param("token", token)).andExpect(redirectedUrl("/rinkreports/login.html?verified"));
        mvc.perform(get("/api/verify").param("token", token)).andExpect(redirectedUrl("/rinkmetrics/login.html?verification=invalid"));
        mvc.perform(get("/api/verify").param("token", "inventado")).andExpect(redirectedUrl("/rinkmetrics/login.html?verification=invalid"));
        // El enlace de los mails de antes (/api/verificar) también sirve.
        mvc.perform(get("/api/verificar").param("token", "inventado")).andExpect(redirectedUrl("/rinkmetrics/login.html?verification=invalid"));
    }

    @Test
    void resendConfirmationByUserOrEmail() throws Exception {
        register(club("ana")).andExpect(status().isCreated());
        // Un mail por minuto como mucho: el reenvío inmediato no manda otro.
        mvc.perform(post("/api/verification/resend").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"ana\"}")).andExpect(status().isOk());
        verify(mailer, times(1)).send(org.mockito.ArgumentMatchers.argThat(m -> m.to().equals("ana@example.com")));
        // Email inexistente: misma respuesta, sin mail.
        mvc.perform(post("/api/verification/resend").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"nadie@example.com\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.startsWith("Si hay una cuenta")));
        verify(mailer, never()).send(org.mockito.ArgumentMatchers.argThat(m -> m.to().equals("nadie@example.com")));
    }

    @Test
    void recoverThePassword() throws Exception {
        register(club("ana")).andExpect(status().isCreated());
        confirm("ana@example.com");

        String forgot = "{\"email\":\"ANA@example.com\"}";
        mvc.perform(post("/api/password/forgot").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(forgot))
                .andExpect(status().isOk());
        String token = token("ana@example.com", "/reset-password.html");

        String shortPassword = "{\"token\":\"%s\",\"password\":\"corta\"}".formatted(token);
        mvc.perform(post("/api/password/reset").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(shortPassword))
                .andExpect(status().isBadRequest());
        String fresh = "{\"token\":\"%s\",\"password\":\"otra-clave-segura\"}".formatted(token);
        mvc.perform(post("/api/password/reset").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(fresh))
                .andExpect(status().isOk());

        login("ana", "clave-segura").andExpect(status().isUnauthorized());
        login("ana", "otra-clave-segura").andExpect(status().isOk());
        // El enlace no se puede volver a usar.
        mvc.perform(post("/api/password/reset").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(fresh))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.startsWith("El enlace venció o ya se usó")));

        // Email inexistente: misma respuesta y ningún mail.
        mvc.perform(post("/api/password/forgot").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"nadie@example.com\"}")).andExpect(status().isOk());
        verify(mailer, never()).send(org.mockito.ArgumentMatchers.argThat(m -> m.to().equals("nadie@example.com")));
    }

    @Test
    void eachProductHasItsOwnLogin() throws Exception {
        for (UserAccount u : List.of(saved("club.uno", Profile.CLUBFPP), saved("dt.uno", Profile.COACHFPP))) {
            u.verifyEmail();
            users.save(u);
        }
        assertThat(users.findByUsername("club.uno").orElseThrow().getProduct()).isEqualTo(Product.REPORTS);
        assertThat(users.findByUsername("dt.uno").orElseThrow().getProduct()).isEqualTo(Product.METRICS);
        // Desde su producto, entra; desde el otro, no (ni queda la sesión), y la respuesta dice dónde ingresar.
        loginTo("club.uno", "REPORTS").andExpect(status().isOk());
        loginTo("dt.uno", "METRICS").andExpect(status().isOk());
        loginTo("dt.uno", "REPORTS").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("OTHER_PRODUCT"))
                .andExpect(jsonPath("$.login").value("/rinkmetrics/login.html"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("RinkMetrics")));
        loginTo("club.uno", "METRICS").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.login").value("/rinkreports/login.html"));
        // La sesión dice de qué producto es la cuenta.
        mvc.perform(get("/api/session").with(user("club.uno").authorities(() -> "CLUBFPP")))
                .andExpect(jsonPath("$.product").value("REPORTS"));
    }

    @Test
    void theSameEmailCanHaveAnAccountInEachProduct() throws Exception {
        Map<String, Object> coach = club("profe");
        coach.putAll(Map.of("profile", "COACHFPP", "subscription", "COACH_FREE"));
        register(coach).andExpect(status().isCreated());
        // Otro DT con el mismo email: no (mismo producto).
        Map<String, Object> otherCoach = new HashMap<>(coach);
        otherCoach.put("username", "profe2");
        register(otherCoach).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Ya hay una cuenta de RinkMetrics con ese email."));
        // Un jugador de RinkReports con el mismo email: sí.
        Map<String, Object> player = player("profe.jugador");
        player.put("email", "profe@example.com");
        register(player).andExpect(status().isCreated());

        // Cada mail, con la marca de su producto.
        ArgumentCaptor<MailContent> mails = ArgumentCaptor.forClass(MailContent.class);
        verify(mailer, atLeastOnce()).send(mails.capture());
        assertThat(mails.getAllValues()).extracting(MailContent::product, MailContent::subject)
                .contains(org.assertj.core.groups.Tuple.tuple(Product.METRICS, "Confirmá tu cuenta de RinkMetrics"),
                        org.assertj.core.groups.Tuple.tuple(Product.REPORTS, "Confirmá tu cuenta de RinkReports"));

        // "Olvidé mi clave" desde RinkReports va a la cuenta de RinkReports, con su enlace.
        confirm("profe@example.com");
        mvc.perform(post("/api/password/forgot").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"profe@example.com\",\"product\":\"REPORTS\"}")).andExpect(status().isOk());
        verify(mailer, atLeastOnce()).send(mails.capture());
        MailContent reset = mails.getAllValues().stream().filter(m -> m.subject().startsWith("Elegí una clave"))
                .reduce((a, b) -> b).orElseThrow();
        assertThat(reset.product()).isEqualTo(Product.REPORTS);
        assertThat(reset.url()).contains("/reset-password.html?product=reports&token=");
        assertThat(realMailer.html(reset)).contains("RinkReports").doesNotContain(">RinkMetrics<");
    }

    @Test
    void theSameUsernameCanHaveAnAccountInEachProduct() throws Exception {
        register(club("mismo")).andExpect(status().isCreated());
        Map<String, Object> coach = coach("mismo");
        coach.put("password", "clave-del-dt");
        register(coach).andExpect(status().isCreated());
        // Otro "mismo" en RinkReports: no (el usuario es único dentro de cada producto).
        Map<String, Object> again = club("mismo");
        again.put("email", "otro.mismo@example.com");
        register(again).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("El usuario mismo ya existe en RinkReports."));
        for (Product p : Product.values()) {
            UserAccount u = users.findByUsernameAndProduct("mismo", p).orElseThrow();
            u.verifyEmail();
            users.save(u);
        }
        // Sin el producto no se sabe cuál de las dos es.
        assertThat(users.findByUsername("mismo")).isEmpty();
        // Cada uno entra a su producto con su clave, y la sesión es la de esa cuenta.
        MvcResult reports = loginTo("mismo", "REPORTS").andExpect(status().isOk())
                .andExpect(jsonPath("$.home").value("/rinkreports/club.html")).andReturn();
        mvc.perform(get("/api/session").session((MockHttpSession) reports.getRequest().getSession()))
                .andExpect(jsonPath("$.product").value("REPORTS")).andExpect(jsonPath("$.username").value("mismo"));
        mvc.perform(post("/api/login").with(csrf()).param("username", "Mismo").param("password", "clave-del-dt")
                        .param("product", "METRICS"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.home").value("/coach.html"));
        // Con la clave de la otra cuenta, no.
        loginTo("mismo", "METRICS").andExpect(status().isUnauthorized());
        // "Recordarme" queda a nombre de la cuenta de su producto.
        MvcResult remembered = mvc.perform(post("/api/login").with(csrf()).param("username", "mismo")
                .param("password", "clave-del-dt").param("product", "METRICS").param("rememberMe", "true"))
                .andExpect(status().isOk()).andReturn();
        mvc.perform(get("/api/session").cookie(remembered.getResponse().getCookie("REMEMBER_ME")))
                .andExpect(jsonPath("$.product").value("METRICS"));
        assertThat(jdbc.queryForObject("select count(*) from persistent_logins where username = 'METRICS:mismo'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void signupOptions() throws Exception {
        mvc.perform(get("/api/signup/options")).andExpect(status().isOk())
                .andExpect(jsonPath("$.club").isBoolean()).andExpect(jsonPath("$.playerAmp").isBoolean());
    }

    @Test
    void theMailHasTheWebLook() {
        MailContent m = new MailContent("ana@example.com", "Confirmá tu cuenta de RinkMetrics", "Un paso más",
                "Tu cuenta", "Confirmá tu email", "ana", List.of("Gracias por sumarte."), "Confirmar mi email",
                "http://localhost/api/verify?token=abc", "El enlace vale 48 horas.");
        String html = realMailer.html(m);
        assertThat(html).contains("#e6ecef", "#0a7ea4", "cid:logo", "Confirmá tu email", "Hola ana:",
                "href=\"http://localhost/api/verify?token=abc\"", "Confirmar mi email ►", "RinkMetrics");
        assertThat(m.text()).contains("Hola ana:", "Confirmar mi email: http://localhost/api/verify?token=abc");
        assertThat(new String(Mailer.logo(), 1, 3)).isEqualTo("PNG");
    }

    @Test
    void mailPreview() throws Exception {
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
    void clubAndPlayerPlans() throws Exception {
        // El club: el free (hasta el 31/12/2026, sin límite de informes) y el mensual, que todavía no se vende:
        // "Próximamente", con precio a definir.
        mvc.perform(get("/api/plans").param("profile", "CLUBFPP")).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].code").value(org.hamcrest.Matchers.contains("CLUB_FREE", "CLUB_MONTHLY")))
                .andExpect(jsonPath("$[0].price").value(0))
                .andExpect(jsonPath("$[0].reportsPerMonth").value(0))
                .andExpect(jsonPath("$[0].validUntil").value("2026-12-31"))
                .andExpect(jsonPath("$[0].available").value(true))
                .andExpect(jsonPath("$[1].priceUsd").value(20.0))
                // En pesos al dólar de respaldo de los tests ($ 1.500), redondeado hacia abajo a centenas.
                .andExpect(jsonPath("$[1].price").value(30000))
                .andExpect(jsonPath("$[1].dollar").value(1500))
                .andExpect(jsonPath("$[1].available").value(false))
                .andExpect(jsonPath("$[1].priceToBeDefined").value(true));
        Map<String, Object> paid = club("ana.pago");
        paid.put("subscription", "CLUB_MONTHLY");
        register(paid).andExpect(status().isBadRequest());
        // El jugador: el free (hasta el 31/12/2026) y el mensual.
        mvc.perform(get("/api/plans").param("profile", "PLAYERFPP")).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].code").value(org.hamcrest.Matchers.contains("PLAYER_FREE", "PLAYER_MONTHLY")))
                .andExpect(jsonPath("$[0].price").value(0))
                .andExpect(jsonPath("$[0].validUntil").value("2026-12-31"))
                .andExpect(jsonPath("$[0].reportsPerMonth").value(5))
                .andExpect(jsonPath("$[1].priceUsd").value(7.0))
                .andExpect(jsonPath("$[1].price").value(10500))
                .andExpect(jsonPath("$[1].reportsPerMonth").value(4))
                .andExpect(jsonPath("$[1].validUntil").value(org.hamcrest.Matchers.nullValue()));

        // Solo existen los planes mensuales.
        Map<String, Object> other = club("ana.otro");
        other.put("subscription", "CLUB_YEARLY");
        register(other).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Plan de suscripción inválido: CLUB_YEARLY."));
    }

    @Test
    void withoutCsrfTokenPostsAreRejected() throws Exception {
        mvc.perform(post("/api/signup").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(club("ana")))).andExpect(status().isForbidden());
        assertThat(users.count()).isZero();
    }

    @Test
    void theClubOnlyRequestsReportsOfItsClub() throws Exception {
        saved("club", Profile.CLUBFPP);
        ReportService.Job job = mock(ReportService.Job.class);
        when(job.id()).thenReturn(UUID.randomUUID());
        when(job.status()).thenReturn(ReportService.Status.PENDING);
        when(reports.submit(any(), eq("REPORTS:club"))).thenReturn(job);
        String request = "{\"clubId\":%d,\"category\":\"PRIMERA\",\"branch\":\"MASCULINO\"}";

        mvc.perform(post("/api/reports").with(user("club").authorities(() -> "CLUBFPP")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(request.formatted(99)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Solo podés pedir informes de tu club (VELEZ)."));
        assertThat(users.findByUsername("club").orElseThrow().getReportsRequested()).isZero();   // el rechazado no cuenta
        mvc.perform(post("/api/reports").with(user("club").authorities(() -> "CLUBFPP")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(request.formatted(74)))
                .andExpect(status().isAccepted());
        mvc.perform(post("/api/reports").with(user("club").authorities(() -> "CLUBFPP")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(request.formatted(74)))
                .andExpect(status().isAccepted());
        assertThat(users.findByUsername("club").orElseThrow().getReportsRequested()).isEqualTo(2);
    }

    @Test
    void playerAlsoCountsRequestedReports() throws Exception {
        UserAccount u = saved("jug", Profile.PLAYERFPP);
        u.linkPlayer(3851, "Ignacio J Graiño", "PRIMERA", "MASCULINO");
        users.save(u);
        com.madsports.player.PlayerReportService.Job job = mock(com.madsports.player.PlayerReportService.Job.class);
        when(job.id()).thenReturn(UUID.randomUUID());
        when(job.status()).thenReturn(ReportService.Status.PENDING);
        when(playerReports.submit(any(), eq("REPORTS:jug"))).thenReturn(job);

        mvc.perform(post("/api/player/reports").with(user("jug").authorities(() -> "PLAYERFPP")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"year\":2026,\"period\":\"SEASON\"}"))
                .andExpect(status().isAccepted());
        assertThat(users.findByUsername("jug").orElseThrow().getReportsRequested()).isEqualTo(1);
        // Los informes de ejemplo no cuentan.
        mvc.perform(get("/api/sample-player.pdf").with(user("jug").authorities(() -> "PLAYERFPP")))
                .andExpect(status().isOk());
        assertThat(users.findByUsername("jug").orElseThrow().getReportsRequested()).isEqualTo(1);
    }

    @Test
    void freePlayerRequestsUpTo5ReportsPerMonth() throws Exception {
        UserAccount u = saved("cupo", Profile.PLAYERFPP);
        u.linkPlayer(3851, "Ignacio J Graiño", "PRIMERA", "MASCULINO");
        u.subscribe(subscriptions.findByCode("PLAYER_FREE").orElseThrow(), java.time.LocalDate.now());
        users.save(u);
        com.madsports.player.PlayerReportService.Job job = mock(com.madsports.player.PlayerReportService.Job.class);
        when(job.id()).thenReturn(UUID.randomUUID());
        when(job.status()).thenReturn(ReportService.Status.PENDING);
        when(playerReports.submit(any(), eq("REPORTS:cupo"))).thenReturn(job);

        for (int i = 1; i <= 5; i++) {
            requestReport("cupo").andExpect(status().isAccepted());
        }
        // El sexto del mes, no: 429 con el día en que se renueva. No cuenta ni se pide.
        requestReport("cupo").andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.startsWith(
                        "Ya pediste los 5 informes de este mes. El cupo se renueva el 1/")));
        verify(playerReports, times(5)).submit(any(), eq("REPORTS:cupo"));
        assertThat(users.findByUsername("cupo").orElseThrow().getReportsRequested()).isEqualTo(5);
        mvc.perform(get("/api/session").with(user("cupo").authorities(() -> "PLAYERFPP")))
                .andExpect(jsonPath("$.monthlyReports.used").value(5))
                .andExpect(jsonPath("$.monthlyReports.limit").value(5));
    }

    @Test
    void anInvalidRequestDoesNotUseTheQuota() throws Exception {
        UserAccount u = saved("cupo2", Profile.PLAYERFPP);
        u.linkPlayer(3851, "Ignacio J Graiño", "PRIMERA", "MASCULINO");
        u.subscribe(subscriptions.findByCode("PLAYER_FREE").orElseThrow(), java.time.LocalDate.now());
        users.save(u);
        when(playerReports.submit(any(), eq("REPORTS:cupo2"))).thenThrow(new IllegalArgumentException("Período inválido."));
        requestReport("cupo2").andExpect(status().isBadRequest());
        mvc.perform(get("/api/session").with(user("cupo2").authorities(() -> "PLAYERFPP")))
                .andExpect(jsonPath("$.monthlyReports.used").value(0));
    }

    ResultActions requestReport(String username) throws Exception {
        return mvc.perform(post("/api/player/reports").with(user(username).authorities(() -> "PLAYERFPP")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"year\":2026,\"period\":\"SEASON\"}"));
    }

    @Test
    void subscriptionPlansAndValidity() throws Exception {
        // Un plan mensual y uno free por tipo de usuario.
        assertThat(subscriptions.findAll()).extracting(SubscriptionPlan::getCode, SubscriptionPlan::getUserType)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("PLAYER_FREE", "PLAYER"),
                        org.assertj.core.groups.Tuple.tuple("PLAYER_MONTHLY", "PLAYER"),
                        org.assertj.core.groups.Tuple.tuple("CLUB_FREE", "CLUB"),
                        org.assertj.core.groups.Tuple.tuple("CLUB_MONTHLY", "CLUB"),
                        org.assertj.core.groups.Tuple.tuple("COACH_FREE", "COACH"),
                        org.assertj.core.groups.Tuple.tuple("COACH_MONTHLY", "COACH"));

        java.time.LocalDate from = java.time.LocalDate.of(2026, 10, 15);
        assertThat(subscriptions.findByCode("PLAYER_MONTHLY").orElseThrow().to(from)).isEqualTo("2026-11-14");

        UserAccount u = saved("club", Profile.CLUBFPP);
        // A un club no se le puede asignar una suscripción de jugador.
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        u.subscribe(subscriptions.findByCode("PLAYER_MONTHLY").orElseThrow(), from))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("es para jugadores");
        u.subscribe(subscriptions.findByCode("CLUB_MONTHLY").orElseThrow(), from);
        users.save(u);
        UserAccount read = users.findByUsername("club").orElseThrow();
        assertThat(read.getSubscriptionPlan().getName()).isEqualTo("Club mensual");
        assertThat(read.getSubscriptionFrom()).isEqualTo("2026-10-15");
        assertThat(read.getSubscriptionTo()).isEqualTo("2026-11-14");
        mvc.perform(get("/api/session").with(user("club").authorities(() -> "CLUBFPP")))
                .andExpect(jsonPath("$.subscription.kind").value("CLUB_MONTHLY"))
                .andExpect(jsonPath("$.subscription.userType").value("CLUB"))
                .andExpect(jsonPath("$.subscription.to").value("2026-11-14"))
                .andExpect(jsonPath("$.reportsRequested").value(0));
    }

    @Test
    void eachProfileGoesToItsScreen() throws Exception {
        mvc.perform(get("/rinkreports/club.html").with(user("j").authorities(() -> "PLAYERFPP"))).andExpect(redirectedUrl("/rinkreports/player.html"));
        mvc.perform(get("/api/reports/" + UUID.randomUUID()).with(user("j").authorities(() -> "PLAYERFPP")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/rinkreports/player.html").with(user("c").authorities(() -> "CLUBFPP"))).andExpect(redirectedUrl("/rinkreports/club.html"));
        mvc.perform(get("/api/player/reports/" + UUID.randomUUID()).with(user("c").authorities(() -> "CLUBFPP")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/rinkreports/player.html").with(user("j").authorities(() -> "PLAYERFPP"))).andExpect(status().isOk());
    }

    @Test
    void theSessionHasTheUserData() throws Exception {
        saved("club", Profile.CLUBFPP);
        mvc.perform(get("/api/session").with(user("club").authorities(() -> "CLUBFPP")))
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.profiles[0]").value("CLUBFPP"))
                .andExpect(jsonPath("$.club.id").value(74))
                .andExpect(jsonPath("$.club.name").value("VELEZ"))
                .andExpect(jsonPath("$.home").value("/rinkreports/club.html"));
    }
}
