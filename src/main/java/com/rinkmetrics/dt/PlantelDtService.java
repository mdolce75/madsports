package com.rinkmetrics.dt;

import com.rinkmetrics.account.Usuario;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.text.Collator;
import java.time.Clock;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * Jugadores del DT (el ABM de jugadores de la sección Equipo): nombre, apellido, número, si es arquero y en qué equipos
 * juega (uno o más). El plantel de un equipo son sus jugadores activos; no sale de SIDGAD. Las acciones nombran al
 * jugador por su id.
 */
@Service
public class PlantelDtService {

    private static final Pattern NUMERO = Pattern.compile("\\d{1,3}");
    /** Intentos de un alta si otra simultánea del mismo DT toma el mismo id (un doble toque, dos dispositivos). */
    static final int INTENTOS_ALTA = 10;

    /**
     * Orden alfabético en castellano, sin distinguir mayúsculas ni acentos (Álvarez antes que Gómez): el mismo que usan
     * la pantalla y la demo ({@code localeCompare(..., "es", {sensitivity: "base"})}).
     */
    static final Comparator<String> ALFABETICO = alfabetico();

    private static Comparator<String> alfabetico() {
        Collator c = Collator.getInstance(Locale.forLanguageTag("es"));
        c.setStrength(Collator.PRIMARY);
        return c::compare;
    }

    /**
     * Un jugador.
     *
     * @param id      el id con el que lo nombran las acciones
     * @param nombre  nombre y apellido, para mostrar
     * @param nombres solo el nombre (para editarlo)
     * @param equipos ids de los equipos en que juega
     */
    public record JugadorPlantel(int id, String numero, String nombre, String nombres, String apellido,
                                 boolean arquero, List<Long> equipos) {

        static JugadorPlantel of(JugadorDt j) {
            return new JugadorPlantel(j.getJugadorId(), j.getNumero() == null ? "" : j.getNumero(), j.completo(),
                    j.getNombre(), j.getApellido(), j.isArquero(), j.getEquipos().stream().sorted().toList());
        }
    }

    /** Alta o cambio de un jugador: sus datos y los equipos en que juega (al menos uno). */
    public record DatosJugador(String nombre, String apellido, String numero, Boolean arquero, List<Long> equipos) {
    }

    private final JugadorDtRepository jugadores;
    private final EquipoDtService equipos;
    private final Clock clock;
    private final TransactionTemplate tx;

    public PlantelDtService(JugadorDtRepository jugadores, EquipoDtService equipos, Clock clock,
                            PlatformTransactionManager transacciones) {
        this.jugadores = jugadores;
        this.equipos = equipos;
        this.clock = clock;
        this.tx = new TransactionTemplate(transacciones);
    }

    /** Todos los jugadores activos del DT, por apellido. */
    public List<JugadorPlantel> jugadores(Usuario u) {
        return jugadores.findByUsuarioIdAndActivoTrue(u.getId()).stream()
                .sorted(Comparator.comparing(JugadorDt::getApellido, ALFABETICO)
                        .thenComparing(JugadorDt::getNombre, ALFABETICO))
                .map(JugadorPlantel::of).toList();
    }

    /** El plantel de un equipo (sin id, el primero): sus jugadores activos, por número. */
    public List<JugadorPlantel> plantel(Usuario u, Long equipoId) {
        Equipo e = equipos.equipo(u, equipoId);
        return delEquipo(u, e.getId()).stream().map(JugadorPlantel::of).toList();
    }

    /**
     * Alta de un jugador. El id es el siguiente al último del DT: si dos altas simultáneas toman el mismo, la base
     * rechaza la segunda (id único por DT) y se reintenta en una transacción nueva, que ya ve a la primera (y, si tienen
     * el mismo número, avisa que el número ya es de ese jugador).
     */
    public JugadorPlantel alta(Usuario u, DatosJugador d) {
        for (int intento = 1; ; intento++) {
            try {
                return tx.execute(s -> altaUnaVez(u, d));
            } catch (DataIntegrityViolationException e) {
                if (intento == INTENTOS_ALTA) {
                    throw new DtService.Conflicto("No se pudo dar de alta al jugador: probá de nuevo.");
                }
                esperar(intento);
            }
        }
    }

    /** Una espera corta y al azar antes de reintentar, para que las altas que chocaron no vuelvan a chocar. */
    private static void esperar(int intento) {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(5, 20L * intento));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DtService.Conflicto("No se pudo dar de alta al jugador: probá de nuevo.");
        }
    }

    private JugadorPlantel altaUnaVez(Usuario u, DatosJugador d) {
        Set<Long> suyos = equiposValidos(u, d.equipos());
        String numero = numero(d.numero());
        verificarNumero(u, suyos, numero, -1);
        int id = jugadores.findTopByUsuarioIdOrderByJugadorIdDesc(u.getId()).map(JugadorDt::getJugadorId)
                .filter(n -> n >= JugadorDt.PRIMER_ID_PROPIO).map(n -> n + 1).orElse(JugadorDt.PRIMER_ID_PROPIO);
        JugadorDt j = jugadores.saveAndFlush(new JugadorDt(u.getId(), id, texto(d.nombre(), "nombre"),
                texto(d.apellido(), "apellido"), numero, Boolean.TRUE.equals(d.arquero()), suyos, clock.instant()));
        return JugadorPlantel.of(j);
    }

    @Transactional
    public JugadorPlantel modificar(Usuario u, int jugadorId, DatosJugador d) {
        JugadorDt j = suyo(u, jugadorId);
        Set<Long> suyos = equiposValidos(u, d.equipos());
        String numero = numero(d.numero());
        verificarNumero(u, suyos, numero, jugadorId);
        j.cambiar(texto(d.nombre(), "nombre"), texto(d.apellido(), "apellido"), numero, Boolean.TRUE.equals(d.arquero()),
                suyos);
        return JugadorPlantel.of(j);
    }

    /** Baja: el jugador sale de sus equipos; sus acciones quedan en los partidos e informes. */
    @Transactional
    public void baja(Usuario u, int jugadorId) {
        suyo(u, jugadorId).baja();
    }

    private List<JugadorDt> delEquipo(Usuario u, Long equipoId) {
        return jugadores.findByUsuarioIdAndActivoTrue(u.getId()).stream().filter(j -> j.juegaEn(equipoId))
                .sorted(Comparator.comparingInt((JugadorDt j) -> orden(j.getNumero()))
                        .thenComparing(JugadorDt::getApellido, ALFABETICO))
                .toList();
    }

    private JugadorDt suyo(Usuario u, int jugadorId) {
        return jugadores.findByUsuarioIdAndJugadorIdAndActivoTrue(u.getId(), jugadorId)
                .orElseThrow(() -> new DtService.NoEncontrado("Ese jugador no está en tus equipos."));
    }

    /** Al menos un equipo, y todos del DT. */
    private Set<Long> equiposValidos(Usuario u, List<Long> pedidos) {
        Set<Long> ids = new LinkedHashSet<>(pedidos == null ? List.of() : pedidos);
        ids.remove(null);
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("Asigná el jugador a un equipo.");
        }
        ids.forEach(id -> equipos.equipo(u, id));
        return ids;
    }

    /** El número no se repite dentro de un mismo equipo. */
    private void verificarNumero(Usuario u, Set<Long> equiposDelJugador, String numero, int salvo) {
        if (numero == null) {
            return;
        }
        jugadores.findByUsuarioIdAndActivoTrue(u.getId()).stream()
                .filter(j -> j.getJugadorId() != salvo && numero.equals(j.getNumero()))
                .filter(j -> j.getEquipos().stream().anyMatch(equiposDelJugador::contains))
                .findFirst()
                .ifPresent(j -> {
                    throw new DtService.Conflicto("El número " + numero + " ya es de " + j.completo() + " en ese equipo.");
                });
    }

    private static String texto(String s, String campo) {
        String t = s == null ? "" : s.trim().replaceAll("\\s+", " ");
        if (t.isEmpty()) {
            throw new IllegalArgumentException("Escribí el " + campo + " del jugador.");
        }
        if (t.length() > 40) {
            throw new IllegalArgumentException("El " + campo + " es muy largo (hasta 40 letras).");
        }
        return t;
    }

    /** Número de camiseta: de 1 a 3 cifras, sin ceros adelante; vacío = sin número. */
    private static String numero(String s) {
        String t = s == null ? "" : s.trim();
        if (t.isEmpty()) {
            return null;
        }
        if (!NUMERO.matcher(t).matches()) {
            throw new IllegalArgumentException("El número es de 1 a 3 cifras.");
        }
        return String.valueOf(Integer.parseInt(t));
    }

    private static int orden(String numero) {
        return numero == null ? 9999 : Integer.parseInt(numero);
    }
}
