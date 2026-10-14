package com.fppstats.account;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fppstats.catalog.CatalogService;
import com.fppstats.catalog.CatalogService.ClubView;
import com.fppstats.player.RosterService;
import com.fppstats.player.RosterService.RosterPlayer;
import com.fppstats.report.ReportService;
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
    @MockitoBean Mailer mailer;

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
                "email", usuario + "@example.com", "clubId", 74));
        return m;
    }

    /** El jugador no manda nombre ni apellido: se toman del jugador elegido. */
    Map<String, Object> player(String usuario) {
        Map<String, Object> m = club(usuario);
        m.putAll(Map.of("perfil", "PLAYERFPP", "categoria", "PRIMERA", "rama", "MASCULINO",
                "jugadorId", 3851));
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
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(mailer, atLeastOnce()).send(eq(email), anyString(), text.capture());
        String last = text.getAllValues().getLast();
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
        verify(mailer, times(1)).send(eq("ana@example.com"), anyString(), anyString());
        // Email inexistente: misma respuesta, sin mail.
        mvc.perform(post("/api/verificacion/reenviar").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"nadie@example.com\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.startsWith("Si hay una cuenta")));
        verify(mailer, never()).send(eq("nadie@example.com"), anyString(), anyString());
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
        verify(mailer, never()).send(eq("nadie@example.com"), anyString(), anyString());
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
        mvc.perform(post("/api/informes").with(user("club").authorities(() -> "CLUBFPP")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(pedido.formatted(74)))
                .andExpect(status().isAccepted());
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
