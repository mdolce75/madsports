package com.rinkmetrics.account;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rinkmetrics.catalog.CatalogService;
import com.rinkmetrics.catalog.CatalogService.ClubView;
import com.rinkmetrics.player.RosterService;
import com.rinkmetrics.player.RosterService.RosterPlayer;
import com.rinkmetrics.report.ReportService;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Registro, login y acceso por perfil, con la base H2 en memoria y SIDGAD simulado. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:seguridad;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class SecurityTest {

    static final ClubView VELEZ = new ClubView(74, "CAVS", "VELEZ", List.of());

    @Autowired MockMvc mvc;
    @Autowired UsuarioRepository usuarios;
    @Autowired PasswordEncoder encoder;
    @Autowired ObjectMapper json;

    @MockitoBean CatalogService catalog;
    @MockitoBean RosterService roster;
    @MockitoBean ReportService reports;
    @MockitoBean com.rinkmetrics.player.PlayerReportService playerReports;
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
        when(catalog.club(74, 2026)).thenReturn(VELEZ);
        when(catalog.club(eq(99), eq(2026))).thenThrow(new IllegalArgumentException("El club 99 no juega en la FPP en 2026."));
        when(roster.find(74, 2026, "PRIMERA", "MASCULINO", 3851))
                .thenReturn(Optional.of(new RosterPlayer(3851, "Ignacio J Graiño", "Ignacio J", "Graiño",
                        "Primera Masculino")));
    }

    Map<String, Object> club(String usuario) {
        Map<String, Object> m = new HashMap<>(Map.of("perfil", "CLUBFPP", "usuario", usuario, "clave", "clave-segura",
                "email", usuario + "@example.com", "clubId", 74, "suscripcion", "CLUB_MENSUAL"));
        return m;
    }

    /** El jugador no manda nombre ni apellido: se toman del jugador elegido. */
    Map<String, Object> player(String usuario) {
        Map<String, Object> m = club(usuario);
        m.putAll(Map.of("perfil", "PLAYERFPP", "categoria", "PRIMERA", "rama", "MASCULINO",
                "jugadorId", 3851, "suscripcion", "PLAYER_ANUAL"));
        return m;
    }

    ResultActions register(Map<String, Object> body) throws Exception {
        return mvc.perform(post("/api/registro").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    ResultActions login(String usuario, String clave) throws Exception {
        return mvc.perform(post("/api/login").with(csrf()).param("usuario", usuario).param("clave", clave));
    }

    /** Token del último mail enviado a esa dirección con un enlace a {@code path}. */
    String token(String email, String path) {
        ArgumentCaptor<MailContent> mail = ArgumentCaptor.forClass(MailContent.class);
        verify(mailer, atLeastOnce()).send(mail.capture());
        String last = mail.getAllValues().stream().filter(m -> m.to().equals(email)).reduce((a, b) -> b)
                .orElseThrow().url();
        Matcher m = Pattern.compile(Pattern.quote(path) + "\\?token=([A-Za-z0-9_-]+)").matcher(last);
        assertThat(m.find()).as("enlace en el mail:\n%s", last).isTrue();
        return m.group(1);
    }

    /** Confirma el email con el enlace del mail. */
    void confirmar(String email) throws Exception {
        mvc.perform(get("/api/verificar").param("token", token(email, "/api/verificar")))
                .andExpect(status().isSeeOther()).andExpect(redirectedUrl("/login.html?verificado"));
    }

    Usuario saved(String usuario, Perfil perfil) {
        return usuarios.save(new Usuario(usuario, encoder.encode("clave-segura"), usuario + "@example.com", "Ana",
                "Pérez", 74, "VELEZ", "CAVS", EnumSet.of(perfil), Instant.now()));
    }

    @Test
    void sinSesionLasPaginasVanAlLoginYLaApiResponde401() throws Exception {
        // La home es pública (con los informes de ejemplo); la pantalla de club pide sesión.
        mvc.perform(get("/")).andExpect(status().isOk());
        mvc.perform(get("/api/ejemplo.pdf")).andExpect(status().isOk());
        mvc.perform(get("/club.html")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrlPattern("**/login.html"));
        mvc.perform(get("/jugador.html")).andExpect(redirectedUrlPattern("**/login.html"));
        mvc.perform(get("/login.html")).andExpect(status().isOk());
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
        assertThat(u.getSuscripcionType().getCodigo()).isEqualTo("CLUB_MENSUAL");
        assertThat(u.getSuscripcionDesde()).isEqualTo(java.time.LocalDate.now());
        assertThat(u.getSuscripcionHasta()).isEqualTo(java.time.LocalDate.now().plusMonths(1).minusDays(1));
        assertThat(u.getNombre()).isNull();   // el registro no pide nombre ni apellido

        // Sin confirmar el email no se ingresa (con la clave correcta se avisa por qué).
        login("ana.club", "clave-segura").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("EMAIL_NO_VERIFICADO"));
        confirmar("Ana.Club@example.com");
        assertThat(usuarios.findByUsuario("ana.club").orElseThrow().isEmailVerificado()).isTrue();
        login("ANA.CLUB", "clave-segura").andExpect(status().isOk()).andExpect(jsonPath("$.inicio").value("/club.html"));
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
        assertThat(u.getSuscripcionType().getCodigo()).isEqualTo("PLAYER_ANUAL");
        assertThat(u.getNombre()).isEqualTo("Ignacio J");
        assertThat(u.getApellido()).isEqualTo("Graiño");
        assertThat(u.getCategoria()).isEqualTo("PRIMERA");
        assertThat(u.getRama()).isEqualTo("MASCULINO");

        // Un jugador, un usuario.
        Map<String, Object> mismoJugador = player("juan2");
        register(mismoJugador).andExpect(status().isConflict());

        confirmar("juan@example.com");
        login("juan", "clave-segura").andExpect(jsonPath("$.inicio").value("/jugador.html"));
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
        mvc.perform(get("/api/verificar").param("token", token)).andExpect(redirectedUrl("/login.html?verificado"));
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
    void elMailTieneElLookDeLaWeb() {
        MailContent m = new MailContent("ana@example.com", "Confirmá tu cuenta de RinkMetrics", "Un paso más",
                "Tu cuenta", "Confirmá tu email", "ana", List.of("Gracias por sumarte."), "Confirmar mi email",
                "http://localhost/api/verificar?token=abc", "El enlace vale 48 horas.");
        String html = realMailer.html(m);
        assertThat(html).contains("#0b1220", "#22b8f0", "cid:logo", "Confirmá tu email", "Hola ana:",
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
    void planesConPrecioInformesYAhorro() throws Exception {
        mvc.perform(get("/api/suscripciones").param("perfil", "CLUBFPP")).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].codigo").value(org.hamcrest.Matchers.contains(
                        "CLUB_MENSUAL", "CLUB_TRIMESTRAL", "CLUB_SEMESTRAL", "CLUB_ANUAL")))
                .andExpect(jsonPath("$[*].precioUsd").value(org.hamcrest.Matchers.contains(30.0, 80.0, 150.0, 280.0)))
                .andExpect(jsonPath("$[*].informesPorMes").value(org.hamcrest.Matchers.contains(8, 10, 12, 14)))
                // 80 contra 3 x 30 = 90 -> 11%; 150 contra 180 -> 17%; 280 contra 360 -> 22%.
                .andExpect(jsonPath("$[*].ahorroPct").value(org.hamcrest.Matchers.contains(0, 11, 17, 22)))
                .andExpect(jsonPath("$[1].precioPorMes").value(26.67));
        mvc.perform(get("/api/suscripciones").param("perfil", "PLAYERFPP")).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].codigo").value(org.hamcrest.Matchers.contains(
                        "PLAYER_MENSUAL", "PLAYER_TRIMESTRAL", "PLAYER_SEMESTRAL", "PLAYER_ANUAL")))
                .andExpect(jsonPath("$[*].precioUsd").value(org.hamcrest.Matchers.contains(15.0, 40.0, 75.0, 140.0)))
                .andExpect(jsonPath("$[*].informesPorMes").value(org.hamcrest.Matchers.contains(4, 6, 8, 10)))
                .andExpect(jsonPath("$[*].ahorroPct").value(org.hamcrest.Matchers.contains(0, 11, 17, 22)));
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
        com.rinkmetrics.player.PlayerReportService.Job job = mock(com.rinkmetrics.player.PlayerReportService.Job.class);
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
    void tiposDeSuscripcionYVigencia() throws Exception {
        assertThat(suscripciones.findAll()).extracting(SuscripcionType::getCodigo, SuscripcionType::getMeses,
                        SuscripcionType::getTipoUsuario)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("PLAYER_MENSUAL", 1, "PLAYER"),
                        org.assertj.core.groups.Tuple.tuple("PLAYER_TRIMESTRAL", 3, "PLAYER"),
                        org.assertj.core.groups.Tuple.tuple("PLAYER_SEMESTRAL", 6, "PLAYER"),
                        org.assertj.core.groups.Tuple.tuple("PLAYER_ANUAL", 12, "PLAYER"),
                        org.assertj.core.groups.Tuple.tuple("CLUB_MENSUAL", 1, "CLUB"),
                        org.assertj.core.groups.Tuple.tuple("CLUB_TRIMESTRAL", 3, "CLUB"),
                        org.assertj.core.groups.Tuple.tuple("CLUB_SEMESTRAL", 6, "CLUB"),
                        org.assertj.core.groups.Tuple.tuple("CLUB_ANUAL", 12, "CLUB"));

        java.time.LocalDate desde = java.time.LocalDate.of(2026, 10, 15);
        assertThat(suscripciones.findByCodigo("PLAYER_MENSUAL").orElseThrow().hasta(desde)).isEqualTo("2026-11-14");
        assertThat(suscripciones.findByCodigo("CLUB_ANUAL").orElseThrow().hasta(desde)).isEqualTo("2027-10-14");

        Usuario u = saved("club", Perfil.CLUBFPP);
        // A un club no se le puede asignar una suscripción de jugador.
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        u.suscribir(suscripciones.findByCodigo("PLAYER_TRIMESTRAL").orElseThrow(), desde))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("es para jugadores");
        u.suscribir(suscripciones.findByCodigo("CLUB_TRIMESTRAL").orElseThrow(), desde);
        usuarios.save(u);
        Usuario leido = usuarios.findByUsuario("club").orElseThrow();
        assertThat(leido.getSuscripcionType().getNombre()).isEqualTo("Club trimestral");
        assertThat(leido.getSuscripcionDesde()).isEqualTo("2026-10-15");
        assertThat(leido.getSuscripcionHasta()).isEqualTo("2027-01-14");
        mvc.perform(get("/api/sesion").with(user("club").authorities(() -> "CLUBFPP")))
                .andExpect(jsonPath("$.suscripcion.tipo").value("CLUB_TRIMESTRAL"))
                .andExpect(jsonPath("$.suscripcion.tipoUsuario").value("CLUB"))
                .andExpect(jsonPath("$.suscripcion.hasta").value("2027-01-14"))
                .andExpect(jsonPath("$.informesPedidos").value(0));
    }

    @Test
    void cadaPerfilVaASuPantalla() throws Exception {
        mvc.perform(get("/club.html").with(user("j").authorities(() -> "PLAYERFPP"))).andExpect(redirectedUrl("/jugador.html"));
        mvc.perform(get("/api/informes/" + UUID.randomUUID()).with(user("j").authorities(() -> "PLAYERFPP")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/jugador.html").with(user("c").authorities(() -> "CLUBFPP"))).andExpect(redirectedUrl("/club.html"));
        mvc.perform(get("/api/jugador/informes/" + UUID.randomUUID()).with(user("c").authorities(() -> "CLUBFPP")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/jugador.html").with(user("j").authorities(() -> "PLAYERFPP"))).andExpect(status().isOk());
    }

    @Test
    void laSesionTraeLosDatosDelUsuario() throws Exception {
        saved("club", Perfil.CLUBFPP);
        mvc.perform(get("/api/sesion").with(user("club").authorities(() -> "CLUBFPP")))
                .andExpect(jsonPath("$.autenticado").value(true))
                .andExpect(jsonPath("$.perfiles[0]").value("CLUBFPP"))
                .andExpect(jsonPath("$.club.id").value(74))
                .andExpect(jsonPath("$.club.nombre").value("VELEZ"))
                .andExpect(jsonPath("$.inicio").value("/club.html"));
    }
}
