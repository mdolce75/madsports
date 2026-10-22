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
public class LimiteIntentos {

    /** Qué se limita y en qué ventana (el máximo de cada uno es configurable). */
    public enum Tipo {
        /** Ingresos fallidos de un mismo usuario. */
        LOGIN_USUARIO(Duration.ofMinutes(15)),
        /** Ingresos fallidos desde una misma IP (varios usuarios probados). */
        LOGIN_IP(Duration.ofMinutes(15)),
        /** Altas de cuenta desde una misma IP. */
        REGISTRO_IP(Duration.ofHours(1)),
        /** Pedidos de mails (reenviar confirmación, recuperar la clave) desde una misma IP. */
        MAILS_IP(Duration.ofHours(1));

        final Duration ventana;

        Tipo(Duration ventana) {
            this.ventana = ventana;
        }
    }

    private record Regla(int max, Duration ventana, Cache<String, AtomicInteger> contadores) {
    }

    private final Map<Tipo, Regla> reglas = new EnumMap<>(Tipo.class);

    public LimiteIntentos(@Value("${rinkmetrics.limites.login-usuario:5}") int loginUsuario,
                          @Value("${rinkmetrics.limites.login-ip:20}") int loginIp,
                          @Value("${rinkmetrics.limites.registro-ip:5}") int registroIp,
                          @Value("${rinkmetrics.limites.mails-ip:10}") int mailsIp) {
        regla(Tipo.LOGIN_USUARIO, loginUsuario);
        regla(Tipo.LOGIN_IP, loginIp);
        regla(Tipo.REGISTRO_IP, registroIp);
        regla(Tipo.MAILS_IP, mailsIp);
    }

    private void regla(Tipo tipo, int max) {
        // expireAfterWrite: la ventana arranca con el primer intento (sumar no vuelve a escribir la entrada).
        reglas.put(tipo, new Regla(max, tipo.ventana,
                Caffeine.newBuilder().expireAfterWrite(tipo.ventana).maximumSize(100_000).build()));
    }

    /** ¿La clave ya llegó al máximo de su ventana? */
    public boolean bloqueado(Tipo tipo, String clave) {
        Regla r = reglas.get(tipo);
        AtomicInteger n = r.contadores().getIfPresent(normalizar(clave));
        return n != null && n.get() >= r.max();
    }

    /** Suma un intento a la clave. */
    public void sumar(Tipo tipo, String clave) {
        reglas.get(tipo).contadores().get(normalizar(clave), k -> new AtomicInteger()).incrementAndGet();
    }

    /** Borra los intentos de la clave (un ingreso correcto limpia los fallidos de ese usuario). */
    public void limpiar(Tipo tipo, String clave) {
        reglas.get(tipo).contadores().invalidate(normalizar(clave));
    }

    /** Minutos de la ventana del tipo, para el aviso y el header Retry-After. */
    public long minutos(Tipo tipo) {
        return reglas.get(tipo).ventana().toMinutes();
    }

    private static String normalizar(String clave) {
        return clave == null ? "" : clave.trim().toLowerCase(Locale.ROOT);
    }
}
