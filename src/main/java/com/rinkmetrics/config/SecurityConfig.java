package com.rinkmetrics.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rinkmetrics.account.Perfil;
import com.rinkmetrics.account.UsuarioRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
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
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;

/**
 * Registro y login con sesión. Cada pantalla pide su perfil: la de club (/) CLUBFPP y la de jugador
 * (/jugador.html) PLAYERFPP. La API responde 401/403 en JSON; las páginas redirigen al login o a la pantalla del
 * perfil del usuario. CSRF con cookie XSRF-TOKEN que la web devuelve en el header X-XSRF-TOKEN.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final RequestMatcher API = req -> req.getRequestURI().startsWith(req.getContextPath() + "/api/");

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper json, UsuarioRepository usuarios)
            throws Exception {
        http
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/", "/index.html", "/login.html", "/registro.html", "/olvido.html",
                                "/restablecer.html", "/css/**", "/js/**", "/error",
                                "/api/registro", "/api/registro/**", "/api/sesion", "/api/catalogo", "/api/ejemplo.pdf",
                                "/api/ejemplo-jugador.pdf", "/api/verificar", "/api/verificacion/**", "/api/clave/**", "/api/suscripciones")
                        .permitAll()
                        .requestMatchers("/club.html", "/api/informes", "/api/informes/**")
                        .hasAuthority(Perfil.CLUBFPP.name())
                        .requestMatchers("/jugador.html", "/api/jugador/**").hasAuthority(Perfil.PLAYERFPP.name())
                        .anyRequest().authenticated())
                .formLogin(f -> f
                        .loginPage("/login.html")
                        .loginProcessingUrl("/api/login")
                        .usernameParameter("usuario")
                        .passwordParameter("clave")
                        .successHandler((req, res, auth) -> {
                            // Al ingresar, Spring cambia el token CSRF: se genera ya para que la cookie nueva
                            // viaje en esta respuesta (también si se rechaza por email sin confirmar).
                            CsrfToken token = (CsrfToken) req.getAttribute(CsrfToken.class.getName());
                            if (token != null) {
                                token.getToken();
                            }
                            // Clave correcta pero email sin confirmar: no se ingresa. Se chequea después de la clave
                            // para no revelar a quien no la sabe si la cuenta existe.
                            if (usuarios.findByUsuario(auth.getName()).filter(u -> !u.isEmailVerificado()).isPresent()) {
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
                        .failureHandler((req, res, e) -> write(res, json, HttpStatus.UNAUTHORIZED,
                                Map.of("detail", "Usuario o clave incorrectos.")))
                        .permitAll())
                .logout(l -> l
                        .logoutUrl("/api/logout")
                        .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler()))
                .exceptionHandling(e -> e
                        .defaultAuthenticationEntryPointFor(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED), API)
                        .defaultAuthenticationEntryPointFor(new LoginUrlAuthenticationEntryPoint("/login.html"),
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
                .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class);
        return http.build();
    }

    /** Pantalla de inicio según el perfil del usuario. */
    public static String home(Collection<? extends GrantedAuthority> authorities) {
        if (has(authorities, Perfil.CLUBFPP)) {
            return "/club.html";
        }
        if (has(authorities, Perfil.PLAYERFPP)) {
            return "/jugador.html";
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
