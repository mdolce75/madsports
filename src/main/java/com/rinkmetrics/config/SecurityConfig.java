package com.rinkmetrics.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rinkmetrics.account.Perfil;
import com.rinkmetrics.account.UsuarioRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.rememberme.JdbcTokenRepositoryImpl;
import org.springframework.security.web.authentication.rememberme.PersistentTokenBasedRememberMeServices;
import org.springframework.security.web.authentication.rememberme.PersistentTokenRepository;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.sql.DataSource;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.Locale;
import java.util.UUID;
import java.util.Map;

/**
 * Registro y login con sesión. Cada pantalla pide su perfil: la de club (/club.html) CLUBFPP o CLUBAMP y la de
 * jugador (/jugador.html) PLAYERFPP o PLAYERAMP, y la del DT (/dt.html) DTFPP o DTAMP; la federación sale del perfil
 * del usuario. La API responde 401/403 en JSON; las páginas redirigen al login o a la pantalla del
 * perfil del usuario. CSRF con cookie XSRF-TOKEN que la web devuelve en el header X-XSRF-TOKEN.
 * <p>
 * Los intentos de ingreso fallidos, las altas de cuenta y los pedidos de mails tienen un límite por usuario o por IP
 * ({@link LimiteIntentos}): al superarlo, la API responde 429.
 * <p>
 * La sesión dura 8 h sin actividad. Con "Recordarme" (parámetro {@code recordarme} del login) queda además la cookie
 * RECORDARME por 30 días: si la sesión se pierde (venció, se cerró el navegador o se reinició la app), el próximo
 * pedido vuelve a entrar solo. El token se guarda en la base ({@code persistent_logins}), cambia en cada uso y se borra
 * al salir o al cambiar la clave.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final RequestMatcher API = req -> req.getRequestURI().startsWith(req.getContextPath() + "/api/");

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper json, UsuarioRepository usuarios,
                                            PersistentTokenBasedRememberMeServices recordarme,
                                            LimiteIntentos limites) throws Exception {
        http
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/", "/index.html", "/informes.html", "/dt-inicio.html", "/dt-demo.html", "/por-que.html", "/madsports", "/madsports.html", "/rinkreports", "/rinkreports/", "/rinkmetrics", "/rinkmetrics/", "/login.html", "/registro.html", "/olvido.html",
                                "/restablecer.html", "/css/**", "/js/**", "/img/**", "/dt-sw.js", "/error",
                                "/api/registro", "/api/registro/**", "/api/sesion", "/api/catalogo", "/api/ejemplo.pdf",
                                "/api/ejemplo-jugador.pdf", "/api/verificar", "/api/verificacion/**", "/api/clave/**", "/api/suscripciones",
                                "/actuator/health", "/actuator/health/**")
                        .permitAll()
                        .requestMatchers("/club.html", "/api/informes", "/api/informes/**")
                        .hasAnyAuthority(Perfil.CLUBFPP.name(), Perfil.CLUBAMP.name())
                        .requestMatchers("/jugador.html", "/api/jugador/**")
                        .hasAnyAuthority(Perfil.PLAYERFPP.name(), Perfil.PLAYERAMP.name())
                        .requestMatchers("/dt.html", "/api/dt/**")
                        .hasAnyAuthority(Perfil.DTFPP.name(), Perfil.DTAMP.name())
                        .anyRequest().authenticated())
                .formLogin(f -> f
                        .loginPage("/login.html")
                        .loginProcessingUrl("/api/login")
                        .usernameParameter("usuario")
                        .passwordParameter("clave")
                        .successHandler((req, res, auth) -> {
                            limites.limpiar(LimiteIntentos.Tipo.LOGIN_USUARIO, auth.getName());
                            // Al ingresar, Spring cambia el token CSRF: se genera ya para que la cookie nueva
                            // viaje en esta respuesta (también si se rechaza por email sin confirmar).
                            CsrfToken token = (CsrfToken) req.getAttribute(CsrfToken.class.getName());
                            if (token != null) {
                                token.getToken();
                            }
                            // Clave correcta pero email sin confirmar: no se ingresa. Se chequea después de la clave
                            // para no revelar a quien no la sabe si la cuenta existe.
                            if (usuarios.findByUsuario(auth.getName()).filter(u -> !u.isEmailVerificado()).isPresent()) {
                                recordarme.logout(req, res, auth);   // sin confirmar, tampoco queda recordado
                                SecurityContextHolder.clearContext();
                                HttpSession session = req.getSession(false);
                                if (session != null) {
                                    session.invalidate();
                                }
                                write(res, json, HttpStatus.UNAUTHORIZED, Map.of("code", "EMAIL_NO_VERIFICADO",
                                        "detail", "Confirmá tu email para ingresar: te mandamos un enlace al registrarte."));
                                return;
                            }
                            write(res, json, HttpStatus.OK, Map.of("inicio", home(auth.getAuthorities())));
                        })
                        .failureHandler((req, res, e) -> {
                            // Cuenta el intento fallido para el usuario y para la IP (LimiteIntentosFilter corta
                            // los siguientes al llegar al máximo).
                            limites.sumar(LimiteIntentos.Tipo.LOGIN_USUARIO, req.getParameter("usuario"));
                            limites.sumar(LimiteIntentos.Tipo.LOGIN_IP, req.getRemoteAddr());
                            write(res, json, HttpStatus.UNAUTHORIZED, Map.of("detail", "Usuario o clave incorrectos."));
                        })
                        .permitAll())
                .rememberMe(r -> r.rememberMeServices(recordarme))
                .logout(l -> l
                        .logoutUrl("/api/logout")
                        .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler()))
                .exceptionHandling(e -> e
                        .defaultAuthenticationEntryPointFor(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED), API)
                        // Una pantalla sin sesión va al ingreso, que después vuelve a esa misma pantalla.
                        .defaultAuthenticationEntryPointFor((req, res, ex) -> res.sendRedirect(req.getContextPath()
                                        + "/login.html?volver=" + URLEncoder.encode(req.getRequestURI().substring(
                                        req.getContextPath().length()), StandardCharsets.UTF_8)),
                                AnyRequestMatcher.INSTANCE)
                        .accessDeniedHandler((req, res, ex) -> {
                            if (API.matches(req)) {
                                write(res, json, HttpStatus.FORBIDDEN, Map.of("detail",
                                        ex.getMessage() == null || ex.getMessage().equals("Access Denied")
                                                ? "Tu perfil no tiene acceso a esta sección." : ex.getMessage()));
                            } else {
                                Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                                res.sendRedirect(req.getContextPath()
                                        + (auth == null ? "/login.html" : home(auth.getAuthorities())));
                            }
                        }))
                .csrf(c -> c
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class)
                .addFilterBefore(new LimiteIntentosFilter(limites, json), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /** Pantalla de inicio según el perfil del usuario. */
    public static String home(Collection<? extends GrantedAuthority> authorities) {
        if (has(authorities, Perfil.CLUBFPP) || has(authorities, Perfil.CLUBAMP)) {
            return "/club.html";
        }
        if (has(authorities, Perfil.PLAYERFPP) || has(authorities, Perfil.PLAYERAMP)) {
            return "/jugador.html";
        }
        if (has(authorities, Perfil.DTFPP) || has(authorities, Perfil.DTAMP)) {
            return "/dt.html";
        }
        return "/login.html?sinPerfil";
    }

    private static boolean has(Collection<? extends GrantedAuthority> authorities, Perfil perfil) {
        return authorities.stream().anyMatch(a -> a.getAuthority().equals(perfil.name()));
    }

    @Bean
    UserDetailsService userDetailsService(UsuarioRepository usuarios) {
        return username -> usuarios.findByUsuario(username == null ? "" : username.trim().toLowerCase(Locale.ROOT))
                .map(u -> User.withUsername(u.getUsuario())
                        .password(u.getClave())
                        .authorities(u.getPerfiles().stream().map(p -> new SimpleGrantedAuthority(p.name())).toList())
                        .build())
                .orElseThrow(() -> new UsernameNotFoundException(username));
    }

    /** Dispositivos recordados, en la tabla {@code persistent_logins} (V13). */
    @Bean
    PersistentTokenRepository dispositivosRecordados(DataSource dataSource) {
        JdbcTokenRepositoryImpl repo = new JdbcTokenRepositoryImpl();
        repo.setDataSource(dataSource);
        return repo;
    }

    @Bean
    PersistentTokenBasedRememberMeServices recordarme(UserDetailsService usuarios, PersistentTokenRepository tokens,
                                                      @Value("${rinkmetrics.recordarme.clave:}") String clave,
                                                      @Value("${rinkmetrics.recordarme.dias:30}") int dias) {
        PersistentTokenBasedRememberMeServices s = new PersistentTokenBasedRememberMeServices(
                clave.isBlank() ? UUID.randomUUID().toString() : clave, usuarios, tokens);
        s.setParameter("recordarme");
        s.setCookieName("RECORDARME");
        s.setTokenValiditySeconds((int) Duration.ofDays(dias).toSeconds());
        return s;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    private static void write(HttpServletResponse res, ObjectMapper json, HttpStatus status, Object body)
            throws IOException {
        res.setStatus(status.value());
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding("UTF-8");
        json.writeValue(res.getWriter(), body);
    }

    /** Fuerza a generar el token CSRF en cada respuesta, así la cookie XSRF-TOKEN está antes del primer POST. */
    static final class CsrfCookieFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                throws ServletException, IOException {
            CsrfToken token = (CsrfToken) req.getAttribute(CsrfToken.class.getName());
            if (token != null) {
                token.getToken();
            }
            chain.doFilter(req, res);
        }
    }
}
