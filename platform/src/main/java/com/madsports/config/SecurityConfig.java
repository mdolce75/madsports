package com.madsports.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.madsports.account.Profile;
import com.madsports.account.Product;
import com.madsports.account.UserAccount;
import com.madsports.account.UserAccountRepository;
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
import java.util.UUID;
import java.util.Map;

/**
 * Registro y login con sesión. Cada pantalla pide su perfil: la de club (/rinkreports/club.html) CLUBFPP o CLUBAMP
 * y la de jugador (/rinkreports/player.html) PLAYERFPP o PLAYERAMP, y la del DT (/coach.html) COACHFPP, COACHAMP o COACHOTR; la federación sale del perfil
 * del usuario. La API responde 401/403 en JSON; las páginas redirigen al login o a la pantalla del
 * perfil del usuario. CSRF con cookie XSRF-TOKEN que la web devuelve en el header X-XSRF-TOKEN.
 * <p>
 * Los intentos de ingreso fallidos, las altas de cuenta y los pedidos de mails tienen un límite por usuario o por IP
 * ({@link AttemptLimiter}): al superarlo, la API responde 429.
 * <p>
 * La sesión dura 8 h sin actividad. Con "Recordarme" (parámetro {@code rememberMe} del login) queda además la cookie
 * REMEMBER_ME por 30 días: si la sesión se pierde (venció, se cerró el navegador o se reinició la app), el próximo
 * pedido vuelve a entrar solo. El token se guarda en la base ({@code persistent_logins}), cambia en cada uso y se borra
 * al salir o al cambiar la clave.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final RequestMatcher API = req -> req.getRequestURI().startsWith(req.getContextPath() + "/api/");

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper json, UserAccountRepository users,
                                            PersistentTokenBasedRememberMeServices rememberMe,
                                            AttemptLimiter limiter) throws Exception {
        http
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/", "/index.html", "/madsports", "/madsports.html", "/rinkreports", "/rinkreports/",
                                "/rinkreports/index.html", "/rinkreports/sample.html", "/rinkreports/why.html", "/rinkreports/login.html",
                                "/rinkreports/signup.html", "/rinkmetrics", "/rinkmetrics/", "/rinkmetrics/index.html",
                                "/rinkmetrics/why.html", "/rinkmetrics/login.html", "/rinkmetrics/signup.html", "/coach-demo.html",
                                "/forgot-password.html", "/reset-password.html", "/css/**", "/js/**", "/img/**", "/coach-sw.js",
                                "/dt-sw.js", "/error",
                                "/api/signup", "/api/signup/**", "/api/session", "/api/catalog", "/api/catalog/club", "/api/sample.pdf",
                                "/api/sample.html", "/api/sample.xlsx", "/api/sample-player.html", "/api/sample-player.xlsx",
                                "/api/sample-player.pdf", "/api/verify", "/api/verificar", "/api/verification/**", "/api/password/**", "/api/plans",
                                "/actuator/health", "/actuator/health/**")
                        .permitAll()
                        .requestMatchers(WebConfig.OLD_PAGES.keySet().toArray(String[]::new)).permitAll()
                        .requestMatchers("/rinkreports/club.html", "/api/reports", "/api/reports/**")
                        .hasAnyAuthority(Profile.CLUBFPP.name(), Profile.CLUBAMP.name())
                        .requestMatchers("/rinkreports/player.html", "/api/player/**")
                        .hasAnyAuthority(Profile.PLAYERFPP.name(), Profile.PLAYERAMP.name())
                        .requestMatchers("/coach.html", "/api/coach/**")
                        .hasAnyAuthority(Profile.COACHFPP.name(), Profile.COACHAMP.name(), Profile.COACHOTR.name())
                        .anyRequest().authenticated())
                .formLogin(f -> f
                        .loginPage(Product.METRICS.login())
                        .loginProcessingUrl("/api/login")
                        .usernameParameter("username")
                        .passwordParameter("password")
                        .successHandler((req, res, auth) -> {
                            // El mismo nombre con que se cuentan los fallidos: el del pedido ("REPORTS:ana" con el
                            // producto, LoginProductFilter; sin él, como vino).
                            limiter.clear(AttemptLimiter.Kind.LOGIN_USER, req.getParameter("username"));
                            // Al ingresar, Spring cambia el token CSRF: se genera ya para que la cookie nueva
                            // viaje en esta respuesta (también si se rechaza por email sin confirmar).
                            CsrfToken token = (CsrfToken) req.getAttribute(CsrfToken.class.getName());
                            if (token != null) {
                                token.getToken();
                            }
                            // Clave correcta pero email sin confirmar, o cuenta de otro producto: no se ingresa. Se
                            // chequea después de la clave para no revelar a quien no la sabe si la cuenta existe.
                            UserAccount u = users.findByPrincipal(auth.getName()).orElse(null);
                            if (u != null && !u.isEmailVerified()) {
                                reject(req, res, auth, rememberMe);   // sin confirmar, tampoco queda recordado
                                write(res, json, HttpStatus.UNAUTHORIZED, Map.of("code", "EMAIL_NOT_VERIFIED",
                                        "detail", "Confirmá tu email para ingresar: te mandamos un enlace al registrarte."));
                                return;
                            }
                            // Cada producto tiene su ingreso: la pantalla manda el suyo (product=REPORTS o METRICS).
                            Product request = Product.parse(req.getParameter("product"));
                            if (u != null && request != null && u.getProduct() != request) {
                                reject(req, res, auth, rememberMe);
                                write(res, json, HttpStatus.UNAUTHORIZED, Map.of("code", "OTHER_PRODUCT",
                                        "detail", "Tu cuenta es de " + u.getProduct().displayName() + ": ingresá desde "
                                                + u.getProduct().displayName() + ".",
                                        "login", u.getProduct().login()));
                                return;
                            }
                            write(res, json, HttpStatus.OK, Map.of("home", home(auth.getAuthorities())));
                        })
                        .failureHandler((req, res, e) -> {
                            // Cuenta el intento fallido para el usuario y para la IP (AttemptLimitFilter corta
                            // los siguientes al llegar al máximo).
                            limiter.add(AttemptLimiter.Kind.LOGIN_USER, req.getParameter("username"));
                            limiter.add(AttemptLimiter.Kind.LOGIN_IP, req.getRemoteAddr());
                            write(res, json, HttpStatus.UNAUTHORIZED, Map.of("detail", "Usuario o clave incorrectos."));
                        })
                        .permitAll())
                .rememberMe(r -> r.rememberMeServices(rememberMe))
                .logout(l -> l
                        .logoutUrl("/api/logout")
                        .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler()))
                .exceptionHandling(e -> e
                        .defaultAuthenticationEntryPointFor(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED), API)
                        // Una pantalla sin sesión va al ingreso de su producto, que después vuelve a esa pantalla.
                        .defaultAuthenticationEntryPointFor((req, res, ex) -> {
                                    String screen = req.getRequestURI().substring(req.getContextPath().length());
                                    res.sendRedirect(req.getContextPath() + login(screen) + "?returnTo="
                                            + URLEncoder.encode(screen, StandardCharsets.UTF_8));
                                },
                                AnyRequestMatcher.INSTANCE)
                        .accessDeniedHandler((req, res, ex) -> {
                            if (API.matches(req)) {
                                write(res, json, HttpStatus.FORBIDDEN, Map.of("detail",
                                        ex.getMessage() == null || ex.getMessage().equals("Access Denied")
                                                ? "Tu perfil no tiene acceso a esta sección." : ex.getMessage()));
                            } else {
                                Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                                String screen = req.getRequestURI().substring(req.getContextPath().length());
                                res.sendRedirect(req.getContextPath()
                                        + (auth == null ? login(screen) : home(auth.getAuthorities())));
                            }
                        }))
                .csrf(c -> c
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class)
                .addFilterBefore(new AttemptLimitFilter(limiter, json), UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(new LoginProductFilter(), AttemptLimitFilter.class);
        return http.build();
    }

    /** Saca la sesión recién creada (y el "Recordarme") de un ingreso rechazado. */
    private static void reject(HttpServletRequest req, HttpServletResponse res, Authentication auth,
                                 PersistentTokenBasedRememberMeServices rememberMe) {
        rememberMe.logout(req, res, auth);
        SecurityContextHolder.clearContext();
        HttpSession session = req.getSession(false);
        if (session != null) {
            session.invalidate();
        }
    }

    /** Ingreso del producto de una pantalla: las de RinkReports, el suyo; el resto, el de RinkMetrics. */
    static String login(String screen) {
        return (screen.startsWith("/rinkreports/") ? Product.REPORTS : Product.METRICS).login();
    }

    /** Pantalla de inicio según el perfil del usuario. */
    public static String home(Collection<? extends GrantedAuthority> authorities) {
        if (has(authorities, Profile.CLUBFPP) || has(authorities, Profile.CLUBAMP)) {
            return "/rinkreports/club.html";
        }
        if (has(authorities, Profile.PLAYERFPP) || has(authorities, Profile.PLAYERAMP)) {
            return "/rinkreports/player.html";
        }
        if (has(authorities, Profile.COACHFPP) || has(authorities, Profile.COACHAMP) || has(authorities, Profile.COACHOTR)) {
            return "/coach.html";
        }
        return Product.METRICS.login() + "?noProfile";
    }

    private static boolean has(Collection<? extends GrantedAuthority> authorities, Profile profile) {
        return authorities.stream().anyMatch(a -> a.getAuthority().equals(profile.name()));
    }

    @Bean
    UserDetailsService userDetailsService(UserAccountRepository users) {
        // Llega "REPORTS:juan": del ingreso (LoginProductFilter) o el nombre que guardó "Recordarme".
        return principal -> users.findByPrincipal(principal)
                .map(u -> User.withUsername(u.principal())
                        .password(u.getPassword())
                        .authorities(u.getProfiles().stream().map(p -> new SimpleGrantedAuthority(p.name())).toList())
                        .build())
                .orElseThrow(() -> new UsernameNotFoundException(principal));
    }

    /** Dispositivos recordados, en la tabla {@code persistent_logins} (V13). */
    @Bean
    PersistentTokenRepository rememberedDevices(DataSource dataSource) {
        JdbcTokenRepositoryImpl repo = new JdbcTokenRepositoryImpl();
        repo.setDataSource(dataSource);
        return repo;
    }

    @Bean
    PersistentTokenBasedRememberMeServices rememberMe(UserDetailsService users, PersistentTokenRepository tokens,
                                                      @Value("${madsports.remember-me.key:}") String key,
                                                      @Value("${madsports.remember-me.days:30}") int days) {
        PersistentTokenBasedRememberMeServices s = new PersistentTokenBasedRememberMeServices(
                key.isBlank() ? UUID.randomUUID().toString() : key, users, tokens);
        s.setParameter("rememberMe");
        s.setCookieName("REMEMBER_ME");
        s.setTokenValiditySeconds((int) Duration.ofDays(days).toSeconds());
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
