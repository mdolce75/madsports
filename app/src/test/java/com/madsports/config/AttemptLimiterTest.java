package com.madsports.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.madsports.account.Profile;
import com.madsports.account.UserAccount;
import com.madsports.account.UserAccountRepository;
import com.madsports.catalog.CatalogService;
import com.madsports.catalog.RosterService;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Límite de intentos: ingresos fallidos por usuario y por IP, altas de cuenta y pedidos de mails por IP. */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:limites;DB_CLOSE_DELAY=-1",
        "madsports.limits.login-user=3", "madsports.limits.login-ip=5",
        "madsports.limits.signup-ip=2", "madsports.limits.mails-ip=2"})
@AutoConfigureMockMvc
class AttemptLimiterTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserAccountRepository users;
    @Autowired PasswordEncoder encoder;
    @MockitoBean CatalogService catalog;
    @MockitoBean RosterService roster;

    @BeforeEach
    void setUp() {
        users.deleteAll();
        for (String name : new String[] {"ana", "beto", "cata"}) {
            UserAccount u = new UserAccount(name, encoder.encode("clave-segura"), name + "@example.com", null, null, 74,
                    "VELEZ", "CAVS", EnumSet.of(Profile.COACHFPP), Instant.now());
            u.verifyEmail();
            users.save(u);
        }
    }

    /** Cada test usa sus propias IPs y usuarios: los contadores son de la app y duran toda la ventana. */
    static RequestPostProcessor from(String ip) {
        return req -> {
            req.setRemoteAddr(ip);
            return req;
        };
    }

    ResultActions login(String ip, String username, String password) throws Exception {
        return mvc.perform(post("/api/login").with(csrf()).with(from(ip)).param("username", username).param("password", password));
    }

    @Test
    void theUserIsBlockedAfterThreeFailedAttempts() throws Exception {
        for (int i = 0; i < 3; i++) {
            login("10.0.0.1", "ana", "mal").andExpect(status().isUnauthorized());
        }
        // Con la clave correcta tampoco entra hasta que vence la ventana.
        login("10.0.0.1", "ana", "clave-segura").andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "900"))
                .andExpect(jsonPath("$.detail").value("Demasiados intentos fallidos. Probá de nuevo en 15 minutos o "
                        + "recuperá tu clave."));
        // El bloqueo es de ese usuario, aunque cambie de IP; otro usuario entra.
        login("10.0.0.9", "ana", "clave-segura").andExpect(status().isTooManyRequests());
        login("10.0.0.1", "beto", "clave-segura").andExpect(status().isOk());
    }

    @Test
    void aSuccessfulLoginClearsTheFailures() throws Exception {
        login("10.0.0.2", "beto", "mal").andExpect(status().isUnauthorized());
        login("10.0.0.2", "beto", "mal").andExpect(status().isUnauthorized());
        login("10.0.0.2", "beto", "clave-segura").andExpect(status().isOk());
        login("10.0.0.2", "beto", "mal").andExpect(status().isUnauthorized());
        login("10.0.0.2", "beto", "mal").andExpect(status().isUnauthorized());
        login("10.0.0.2", "beto", "clave-segura").andExpect(status().isOk());
    }

    @Test
    void theIpIsBlockedWhenTryingManyUsers() throws Exception {
        for (String u : new String[] {"x1", "x2", "x3", "x4", "x5"}) {
            login("10.0.0.3", u, "mal").andExpect(status().isUnauthorized());
        }
        login("10.0.0.3", "cata", "clave-segura").andExpect(status().isTooManyRequests());
        login("10.0.0.4", "cata", "clave-segura").andExpect(status().isOk());
    }

    @Test
    void signupsAndMailsLimitedByIp() throws Exception {
        // Cuentan todos los pedidos, válidos o no.
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/signup").with(csrf()).with(from("10.0.0.5"))
                    .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/signup").with(csrf()).with(from("10.0.0.5"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "3600"));

        String request = json.writeValueAsString(Map.of("email", "ana@example.com"));
        mvc.perform(post("/api/password/forgot").with(csrf()).with(from("10.0.0.6"))
                .contentType(MediaType.APPLICATION_JSON).content(request)).andExpect(status().isOk());
        mvc.perform(post("/api/verification/resend").with(csrf()).with(from("10.0.0.6"))
                .contentType(MediaType.APPLICATION_JSON).content(request)).andExpect(status().isOk());
        mvc.perform(post("/api/password/forgot").with(csrf()).with(from("10.0.0.6"))
                        .contentType(MediaType.APPLICATION_JSON).content(request)).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.detail").value("Demasiados pedidos de mails desde esta conexión. Probá de nuevo "
                        + "en 60 minutos."));
    }
}
