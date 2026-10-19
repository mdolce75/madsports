package com.rinkmetrics.dt;

import com.rinkmetrics.account.EquipoDt;
import com.rinkmetrics.account.Usuario;
import com.rinkmetrics.catalog.CatalogService;
import com.rinkmetrics.player.RosterService;
import com.rinkmetrics.player.RosterService.PlantelJugador;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Plantel de cada equipo del DT (el ABM de la sección Equipo). La primera vez que se pide, se arma con el de las actas
 * de SIDGAD y se guarda; desde ahí, el DT da de alta, corrige y da de baja jugadores. Las acciones nombran al jugador
 * por su id: el de SIDGAD o, para los que agrega el DT, uno propio.
 */
@Service
public class PlantelDtService {

    private static final Pattern NUMERO = Pattern.compile("\\d{1,3}");

    /**
     * Un jugador del plantel.
     *
     * @param id      el id con el que lo nombran las acciones
     * @param nombre  nombre y apellido, para mostrar
     * @param nombres solo el nombre (para editarlo)
     */
    public record JugadorPlantel(int id, String numero, String nombre, String nombres, String apellido,
                                 boolean arquero) {

        static JugadorPlantel of(JugadorDt j) {
            return new JugadorPlantel(j.getJugadorId(), j.getNumero() == null ? "" : j.getNumero(), j.completo(),
                    j.getNombre(), j.getApellido(), j.isArquero());
        }
    }

    /** Alta o cambio de un jugador; categoría y rama dicen de qué equipo del DT (sin ellas, el primero). */
    public record DatosJugador(String categoria, String rama, String nombre, String apellido, String numero,
                               Boolean arquero) {
    }

    private final JugadorDtRepository jugadores;
    private final RosterService roster;
    private final CatalogService catalog;
    private final Clock clock;

    public PlantelDtService(JugadorDtRepository jugadores, RosterService roster, CatalogService catalog, Clock clock) {
        this.jugadores = jugadores;
        this.roster = roster;
        this.catalog = catalog;
        this.clock = clock;
    }

    /** Los jugadores activos del equipo, por número. */
    @Transactional
    public List<JugadorPlantel> plantel(Usuario u, String categoria, String rama) {
        EquipoDt e = DtService.equipo(u, categoria, rama);
        return activos(u, e).stream().map(JugadorPlantel::of).toList();
    }

    @Transactional
    public JugadorPlantel alta(Usuario u, DatosJugador d) {
        EquipoDt e = DtService.equipo(u, d.categoria(), d.rama());
        List<JugadorDt> actuales = activos(u, e);
        String numero = numero(d.numero());
        verificarNumero(actuales, numero, -1);
        int id = jugadores.findTopByUsuarioIdOrderByJugadorIdDesc(u.getId()).map(JugadorDt::getJugadorId)
                .filter(n -> n >= JugadorDt.PRIMER_ID_PROPIO).map(n -> n + 1).orElse(JugadorDt.PRIMER_ID_PROPIO);
        JugadorDt j = jugadores.save(new JugadorDt(u.getId(), e.categoria(), e.rama(), id, texto(d.nombre(), "nombre"),
                texto(d.apellido(), "apellido"), numero, Boolean.TRUE.equals(d.arquero()), clock.instant()));
        return JugadorPlantel.of(j);
    }

    @Transactional
    public JugadorPlantel modificar(Usuario u, int jugadorId, DatosJugador d) {
        EquipoDt e = DtService.equipo(u, d.categoria(), d.rama());
        List<JugadorDt> actuales = activos(u, e);
        JugadorDt j = suyo(actuales, jugadorId);
        String numero = numero(d.numero());
        verificarNumero(actuales, numero, jugadorId);
        j.cambiar(texto(d.nombre(), "nombre"), texto(d.apellido(), "apellido"), numero, Boolean.TRUE.equals(d.arquero()));
        return JugadorPlantel.of(j);
    }

    /** Baja: el jugador sale del plantel; sus acciones quedan en los partidos e informes. */
    @Transactional
    public void baja(Usuario u, String categoria, String rama, int jugadorId) {
        EquipoDt e = DtService.equipo(u, categoria, rama);
        suyo(activos(u, e), jugadorId).baja();
    }

    /** Los activos del equipo; si el plantel nunca se armó, primero lo trae de las actas de SIDGAD. */
    private List<JugadorDt> activos(Usuario u, EquipoDt e) {
        List<JugadorDt> todos = jugadores.findByUsuarioIdAndCategoriaAndRamaOrderByIdAsc(u.getId(), e.categoria(), e.rama());
        if (todos.isEmpty()) {
            todos = desdeSidgad(u, e);
        }
        return todos.stream().filter(JugadorDt::isActivo)
                .sorted(Comparator.comparingInt((JugadorDt j) -> orden(j.getNumero()))
                        .thenComparing(JugadorDt::getApellido, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /** Plantel de las actas: nombre y apellido separados en la última palabra (se pueden corregir después). */
    private List<JugadorDt> desdeSidgad(Usuario u, EquipoDt e) {
        List<PlantelJugador> sidgad;
        try {
            sidgad = roster.plantel(u.federacion(), u.getClubId(), catalog.defaultYear(), e.categoria(), e.rama());
        } catch (RuntimeException ex) {
            return List.of();   // sin SIDGAD, se vuelve a intentar la próxima vez
        }
        return sidgad.stream().map(p -> {
            String completo = p.nombre() == null ? "" : p.nombre().trim().replaceAll("\\s+", " ");
            int corte = completo.lastIndexOf(' ');
            String nombre = corte < 0 ? "" : completo.substring(0, corte);
            String apellido = corte < 0 ? completo : completo.substring(corte + 1);
            return jugadores.save(new JugadorDt(u.getId(), e.categoria(), e.rama(), p.id(), corto(nombre), corto(apellido),
                    numeroSidgad(p.numero()), p.arquero(), clock.instant()));
        }).toList();
    }

    private static JugadorDt suyo(List<JugadorDt> actuales, int jugadorId) {
        return actuales.stream().filter(j -> j.getJugadorId() == jugadorId).findFirst()
                .orElseThrow(() -> new DtService.NoEncontrado("Ese jugador no está en el plantel."));
    }

    private static void verificarNumero(List<JugadorDt> actuales, String numero, int salvo) {
        if (numero == null) {
            return;
        }
        actuales.stream().filter(j -> j.getJugadorId() != salvo && numero.equals(j.getNumero())).findFirst()
                .ifPresent(j -> {
                    throw new DtService.Conflicto("El número " + numero + " ya es de " + j.completo() + ".");
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

    private static String numeroSidgad(String s) {
        try {
            return numero(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String corto(String s) {
        return s.length() > 40 ? s.substring(0, 40) : s;
    }

    private static int orden(String numero) {
        return numero == null ? 9999 : Integer.parseInt(numero);
    }
}
