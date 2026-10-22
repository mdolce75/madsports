package com.madsports.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.madsports.config.AttemptLimiter.Kind;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

/**
 * Corta con 429 los pedidos que superan su límite ({@link AttemptLimiter}) antes de que lleguen al ingreso, al registro
 * o al envío de mails. El ingreso cuenta solo los intentos fallidos (lo suma el manejador de error del login); el
 * registro y los mails cuentan cada pedido.
 */
public class AttemptLimitFilter extends OncePerRequestFilter {

    private final AttemptLimiter limiter;
    private final ObjectMapper json;

    public AttemptLimitFilter(AttemptLimiter limiter, ObjectMapper json) {
        this.limiter = limiter;
        this.json = json;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        if (!"POST".equals(req.getMethod())) {
            chain.doFilter(req, res);
            return;
        }
        String path = req.getRequestURI().substring(req.getContextPath().length());
        String ip = req.getRemoteAddr();
        switch (path) {
            case "/api/login" -> {
                if (limiter.blocked(Kind.LOGIN_USER, req.getParameter("username"))
                        || limiter.blocked(Kind.LOGIN_IP, ip)) {
                    reject(res, Kind.LOGIN_USER, "Demasiados intentos fallidos. Probá de nuevo en "
                            + limiter.minutes(Kind.LOGIN_USER) + " minutos o recuperá tu clave.");
                    return;
                }
            }
            case "/api/signup" -> {
                if (limiter.blocked(Kind.SIGNUP_IP, ip)) {
                    reject(res, Kind.SIGNUP_IP, "Demasiados registros desde esta conexión. Probá de nuevo en "
                            + limiter.minutes(Kind.SIGNUP_IP) + " minutos.");
                    return;
                }
                limiter.add(Kind.SIGNUP_IP, ip);
            }
            case "/api/password/forgot", "/api/verification/resend" -> {
                if (limiter.blocked(Kind.MAILS_IP, ip)) {
                    reject(res, Kind.MAILS_IP, "Demasiados pedidos de mails desde esta conexión. Probá de nuevo en "
                            + limiter.minutes(Kind.MAILS_IP) + " minutos.");
                    return;
                }
                limiter.add(Kind.MAILS_IP, ip);
            }
            default -> {
            }
        }
        chain.doFilter(req, res);
    }

    private void reject(HttpServletResponse res, Kind kind, String detail) throws IOException {
        res.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        res.setHeader("Retry-After", String.valueOf(limiter.minutes(kind) * 60));
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding("UTF-8");
        json.writeValue(res.getWriter(), Map.of("detail", detail));
    }
}
