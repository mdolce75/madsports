package com.rinkmetrics.dt;

import com.rinkmetrics.account.Usuario;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Jugadores del DT (el ABM de jugadores de la sección Equipo): nombre, apellido, número, si es arquero y en qué equipos
 * juega (uno o más). El plantel de un equipo son sus jugadores activos; no sale de SIDGAD. Las acciones nombran al
 * jugador por su id.
 */
@Service
public class PlantelDtService {

    private static final Pattern NUMERO = Pattern.compile("\\d{1,3}");

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

    public PlantelDtService(JugadorDtRepository jugadores, EquipoDtService equipos, Clock clock) {
        this.jugadores = jugadores;
        this.equipos = equipos;
        this.clock = clock;
    }

    /** Todos los jugadores activos del DT, por apellido. */
    public List<JugadorPlantel> jugadores(Usuario u) {
        return jugadores.findByUsuarioIdAndActivoTrue(u.getId()).stream()
                .sorted(Comparator.comparing(JugadorDt::getApellido, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(JugadorDt::getNombre, String.CASE_INSENSITIVE_ORDER))
                .map(JugadorPlantel::of).toList();
    }

    /** El plantel de un equipo (sin id, el primero): sus jugadores activos, por número. */
    public List<JugadorPlantel> plantel(Usuario u, Long equipoId) {
        Equipo e = equipos.equipo(u, equipoId);
        return delEquipo(u, e.getId()).stream().map(JugadorPlantel::of).toList();
    }

    @Transactional
    public JugadorPlantel alta(Usuario u, DatosJugador d) {
        Set<Long> suyos = equiposValidos(u, d.equipos());
        String numero = numero(d.numero());
        verificarNumero(u, suyos, numero, -1);
        int id = jugadores.findTopByUsuarioIdOrderByJugadorIdDesc(u.getId()).map(JugadorDt::getJugadorId)
                .filter(n -> n >= JugadorDt.PRIMER_ID_PROPIO).map(n -> n + 1).orElse(JugadorDt.PRIMER_ID_PROPIO);
        JugadorDt j = jugadores.save(new JugadorDt(u.getId(), id, texto(d.nombre(), "nombre"),
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
                        .thenComparing(JugadorDt::getApellido, String.CASE_INSENSITIVE_ORDER))
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
