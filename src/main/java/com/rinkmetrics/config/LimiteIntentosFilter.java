package com.rinkmetrics.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rinkmetrics.config.LimiteIntentos.Tipo;
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
 * Corta con 429 los pedidos que superan su límite ({@link LimiteIntentos}) antes de que lleguen al ingreso, al registro
 * o al envío de mails. El ingreso cuenta solo los intentos fallidos (lo suma el manejador de error del login); el
 * registro y los mails cuentan cada pedido.
 */
public class LimiteIntentosFilter extends OncePerRequestFilter {

    private final LimiteIntentos limites;
    private final ObjectMapper json;

    public LimiteIntentosFilter(LimiteIntentos limites, ObjectMapper json) {
        this.limites = limites;
        this.json = json;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        if (!"POST".equals(req.getMethod())) {
            chain.doFilter(req, res);
            return;
        }
        String ruta = req.getRequestURI().substring(req.getContextPath().length());
        String ip = req.getRemoteAddr();
        switch (ruta) {
            case "/api/login" -> {
                if (limites.bloqueado(Tipo.LOGIN_USUARIO, req.getParameter("usuario"))
                        || limites.bloqueado(Tipo.LOGIN_IP, ip)) {
                    rechazar(res, Tipo.LOGIN_USUARIO, "Demasiados intentos fallidos. Probá de nuevo en "
                            + limites.minutos(Tipo.LOGIN_USUARIO) + " minutos o recuperá tu clave.");
                    return;
                }
            }
            case "/api/registro" -> {
                if (limites.bloqueado(Tipo.REGISTRO_IP, ip)) {
                    rechazar(res, Tipo.REGISTRO_IP, "Demasiados registros desde esta conexión. Probá de nuevo en "
                            + limites.minutos(Tipo.REGISTRO_IP) + " minutos.");
                    return;
                }
                limites.sumar(Tipo.REGISTRO_IP, ip);
            }
            case "/api/clave/olvido", "/api/verificacion/reenviar" -> {
                if (limites.bloqueado(Tipo.MAILS_IP, ip)) {
                    rechazar(res, Tipo.MAILS_IP, "Demasiados pedidos de mails desde esta conexión. Probá de nuevo en "
                            + limites.minutos(Tipo.MAILS_IP) + " minutos.");
                    return;
                }
                limites.sumar(Tipo.MAILS_IP, ip);
            }
            default -> {
            }
        }
        chain.doFilter(req, res);
    }

    private void rechazar(HttpServletResponse res, Tipo tipo, String detalle) throws IOException {
        res.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        res.setHeader("Retry-After", String.valueOf(limites.minutos(tipo) * 60));
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding("UTF-8");
        json.writeValue(res.getWriter(), Map.of("detail", detalle));
    }
}
