package com.madsports.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Límite de intentos contra la fuerza bruta y las altas masivas: cuenta por clave (usuario o IP) dentro de una ventana
 * fija que arranca con el primer intento. Al llegar al máximo, la clave queda bloqueada hasta que vence la ventana.
 * Los contadores viven en memoria (una instancia de la app).
 */
@Component
public class AttemptLimiter {

    /** Qué se limita y en qué ventana (el máximo de cada uno es configurable). */
    public enum Kind {
        /** Ingresos fallidos de un mismo usuario. */
        LOGIN_USER(Duration.ofMinutes(15)),
        /** Ingresos fallidos desde una misma IP (varios usuarios probados). */
        LOGIN_IP(Duration.ofMinutes(15)),
        /** Altas de cuenta desde una misma IP. */
        SIGNUP_IP(Duration.ofHours(1)),
        /** Pedidos de mails (reenviar confirmación, recuperar la clave) desde una misma IP. */
        MAILS_IP(Duration.ofHours(1));

        final Duration window;

        Kind(Duration window) {
            this.window = window;
        }
    }

    private record Rule(int max, Duration window, Cache<String, AtomicInteger> counters) {
    }

    private final Map<Kind, Rule> rules = new EnumMap<>(Kind.class);

    public AttemptLimiter(@Value("${rinkmetrics.limites.login-usuario:5}") int loginUser,
                          @Value("${rinkmetrics.limites.login-ip:20}") int loginIp,
                          @Value("${rinkmetrics.limites.registro-ip:5}") int signupIp,
                          @Value("${rinkmetrics.limites.mails-ip:10}") int mailsIp) {
        rule(Kind.LOGIN_USER, loginUser);
        rule(Kind.LOGIN_IP, loginIp);
        rule(Kind.SIGNUP_IP, signupIp);
        rule(Kind.MAILS_IP, mailsIp);
    }

    private void rule(Kind kind, int max) {
        // expireAfterWrite: la ventana arranca con el primer intento (sumar no vuelve a escribir la entrada).
        rules.put(kind, new Rule(max, kind.window,
                Caffeine.newBuilder().expireAfterWrite(kind.window).maximumSize(100_000).build()));
    }

    /** ¿La clave ya llegó al máximo de su ventana? */
    public boolean blocked(Kind kind, String password) {
        Rule r = rules.get(kind);
        AtomicInteger n = r.counters().getIfPresent(normalize(password));
        return n != null && n.get() >= r.max();
    }

    /** Suma un intento a la clave. */
    public void add(Kind kind, String password) {
        rules.get(kind).counters().get(normalize(password), k -> new AtomicInteger()).incrementAndGet();
    }

    /** Borra los intentos de la clave (un ingreso correcto limpia los fallidos de ese usuario). */
    public void clear(Kind kind, String password) {
        rules.get(kind).counters().invalidate(normalize(password));
    }

    /** Minutos de la ventana del tipo, para el aviso y el header Retry-After. */
    public long minutes(Kind kind) {
        return rules.get(kind).window().toMinutes();
    }

    private static String normalize(String password) {
        return password == null ? "" : password.trim().toLowerCase(Locale.ROOT);
    }
}
