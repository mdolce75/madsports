package com.rinkmetrics.dt;

import com.rinkmetrics.account.Usuario;
import com.rinkmetrics.catalog.CatalogService;
import com.rinkmetrics.catalog.CatalogService.ClubView;
import com.rinkmetrics.catalog.CatalogService.LevelView;
import com.rinkmetrics.dt.EquipoDtService.EquipoView;
import com.rinkmetrics.player.RosterService;
import com.rinkmetrics.player.RosterService.PlantelJugador;
import com.rinkmetrics.player.RosterService.RosterPlayer;
import com.rinkmetrics.sidgad.model.Competition;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.text.Collator;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * Jugadores del DT (el ABM de jugadores de la sección Equipo): nombre, apellido, número, si es arquero y en qué equipos
 * juega (uno o más). El plantel de un equipo son sus jugadores activos; no sale de SIDGAD. Las acciones nombran al
 * jugador por su id. Un equipo se puede armar desde una categoría del club en SIDGAD: su plantel (los jugadores de las
 * actas del equipo) entra entero, salvo los que el DT deja afuera, que quedan como "no incluidos" para sumarlos después.
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
     * @param equipos    ids de los equipos en que juega
     * @param pendientes ids de los equipos en cuyo plantel de SIDGAD figura pero no fue incluido
     */
    public record JugadorPlantel(int id, String numero, String nombre, String nombres, String apellido,
                                 boolean arquero, List<Long> equipos, List<Long> pendientes) {

        static JugadorPlantel of(JugadorDt j) {
            return new JugadorPlantel(j.getJugadorId(), j.getNumero() == null ? "" : j.getNumero(), j.completo(),
                    j.getNombre(), j.getApellido(), j.isArquero(), j.getEquipos().stream().sorted().toList(),
                    j.getPendientes().stream().sorted().toList());
        }
    }

    /**
     * Una categoría del club del DT en SIDGAD (un equipo del club), para armar un equipo con su plantel.
     *
     * @param clave  nivel y, si el club tiene más de un equipo, la letra: "PRIMERA" o "INFANTIL:B"
     * @param rama   MASCULINO o FEMENINO
     * @param nombre "Primera Masculino", "Infantil B Femenino"
     */
    public record CategoriaClub(String clave, String rama, String nombre) {
    }

    /**
     * Un jugador del plantel de SIDGAD de una categoría del club: los que figuran en las actas del equipo.
     *
     * @param numero  su número más usado en las actas (vacío si no figura)
     * @param arquero figura como arquero en sus planillas
     */
    public record JugadorSidgad(int id, String numero, String nombre, String nombres, String apellido, boolean arquero) {
    }

    /** Equipo nuevo: nombre y, si sale de SIDGAD, la categoría del club y los jugadores de su plantel que no van. */
    public record NuevoEquipo(String nombre, String categoria, String rama, List<Integer> excluidos) {
    }

    /** Alta o cambio de un jugador: sus datos y los equipos en que juega (al menos uno). */
    public record DatosJugador(String nombre, String apellido, String numero, Boolean arquero, List<Long> equipos) {
    }

    private final JugadorDtRepository jugadores;
    private final EquipoDtService equipos;
    private final CatalogService catalog;
    private final RosterService roster;
    private final Clock clock;
    private final TransactionTemplate tx;

    public PlantelDtService(JugadorDtRepository jugadores, EquipoDtService equipos, CatalogService catalog,
                            RosterService roster, Clock clock, PlatformTransactionManager transacciones) {
        this.jugadores = jugadores;
        this.equipos = equipos;
        this.catalog = catalog;
        this.roster = roster;
        this.clock = clock;
        this.tx = new TransactionTemplate(transacciones);
    }

    // ---------- Equipos desde SIDGAD ----------

    /** Las categorías del club del DT en SIDGAD (cada equipo del club en cada rama), primero las masculinas. */
    public List<CategoriaClub> categoriasDelClub(Usuario u) {
        ClubView club = catalog.club(u.federacion(), u.getClubId(), catalog.defaultYear());
        List<CategoriaClub> out = new ArrayList<>();
        for (String rama : List.of("MASCULINO", "FEMENINO")) {
            for (LevelView lv : club.categories()) {
                if (lv.branches().contains(rama)) {
                    out.add(new CategoriaClub(lv.key(), rama,
                            lv.label() + " " + new Competition.Category("", rama).branchLabel()));
                }
            }
        }
        return out;
    }

    /** El plantel de SIDGAD de la categoría del club: los jugadores de las actas del equipo, por apellido. */
    public List<JugadorSidgad> plantelSidgad(Usuario u, String categoria, String rama) {
        CategoriaClub c = categoriaDelClub(u, categoria, rama);
        int year = catalog.defaultYear();
        Map<Integer, PlantelJugador> datos = roster.plantel(u.federacion(), u.getClubId(), year, c.clave(), c.rama())
                .stream().collect(Collectors.toMap(PlantelJugador::id, Function.identity(), (a, b) -> a));
        return roster.players(u.federacion(), u.getClubId(), year, c.clave(), c.rama()).stream()
                .map(p -> {
                    PlantelJugador d = datos.get(p.id());
                    return new JugadorSidgad(p.id(), d == null ? "" : numeroSidgad(d.numero()), p.name(),
                            corto(p.firstName()), corto(p.lastName()), d != null && d.arquero());
                })
                .sorted(Comparator.comparing(JugadorSidgad::apellido, ALFABETICO)
                        .thenComparing(JugadorSidgad::nombres, ALFABETICO))
                .toList();
    }

    private CategoriaClub categoriaDelClub(Usuario u, String categoria, String rama) {
        String clave = categoria == null ? "" : categoria.trim().toUpperCase(Locale.ROOT);
        String r = rama == null ? "" : rama.trim().toUpperCase(Locale.ROOT);
        return categoriasDelClub(u).stream().filter(c -> c.clave().equals(clave) && c.rama().equals(r)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Esa categoría no es de tu club."));
    }

    /**
     * Crea un equipo. Con una categoría del club, se arma con su plantel de SIDGAD: entran todos sus jugadores salvo
     * los {@code excluidos}, que quedan como "no incluidos" en el equipo (figuran en la lista y se suman después). Un
     * jugador que el DT ya tiene (por otro equipo) no se duplica: suma este equipo. Sin nombre, el de la categoría.
     */
    @Transactional
    public EquipoView crearEquipo(Usuario u, NuevoEquipo n) {
        if (n.categoria() == null || n.categoria().isBlank()) {
            return equipos.crear(u, n.nombre());
        }
        CategoriaClub c = categoriaDelClub(u, n.categoria(), n.rama());
        List<JugadorSidgad> plantel = plantelSidgad(u, c.clave(), c.rama());
        String nombre = n.nombre() == null || n.nombre().isBlank() ? c.nombre() : n.nombre();
        Equipo e = equipos.nuevo(u, nombre, c.clave(), c.rama());
        Set<Integer> fuera = new HashSet<>(n.excluidos() == null ? List.of() : n.excluidos());
        Set<String> numerosUsados = new HashSet<>();
        // Primero los incluidos: si dos tienen el mismo número, se lo queda uno que juega.
        List<JugadorSidgad> orden = plantel.stream()
                .sorted(Comparator.comparing((JugadorSidgad p) -> fuera.contains(p.id()))).toList();
        for (JugadorSidgad p : orden) {
            boolean incluido = !fuera.contains(p.id());
            JugadorDt j = jugadores.findByUsuarioIdAndJugadorId(u.getId(), p.id()).orElse(null);
            if (j == null) {
                // El número, si no lo usa ya otro del plantel (dos con el mismo número más usado): el segundo, sin número.
                String numero = p.numero().isEmpty() || !numerosUsados.add(p.numero()) ? null : p.numero();
                j = new JugadorDt(u.getId(), p.id(), p.nombres(), p.apellido(), numero, p.arquero(),
                        incluido ? List.of(e.getId()) : List.of(), clock.instant());
            } else {
                if (!j.isActivo()) {
                    j.reactivar();
                }
                if (j.getNumero() != null) {
                    numerosUsados.add(j.getNumero());
                }
            }
            if (incluido) {
                j.incluirEn(e.getId());
            } else {
                j.dejarPendienteEn(e.getId());
            }
            jugadores.save(j);
        }
        return equipos.lista(u).stream().filter(v -> v.id().equals(e.getId())).findFirst().orElseThrow();
    }

    /** Suma al equipo a un jugador "no incluido" (o a cualquiera de sus jugadores), si su número está libre ahí. */
    @Transactional
    public JugadorPlantel incluir(Usuario u, int jugadorId, Long equipoId) {
        JugadorDt j = suyo(u, jugadorId);
        Equipo e = equipos.equipo(u, equipoId);
        verificarNumero(u, Set.of(e.getId()), j.getNumero(), jugadorId);
        j.incluirEn(e.getId());
        return JugadorPlantel.of(j);
    }

    /** Número de las actas ("07") como lo guarda el DT ("7"); vacío si no es un número de camiseta. */
    private static String numeroSidgad(String n) {
        String t = n == null ? "" : n.trim();
        return NUMERO.matcher(t).matches() ? String.valueOf(Integer.parseInt(t)) : "";
    }

    /** Nombre o apellido de SIDGAD, hasta 40 letras (lo que guarda el plantel). */
    private static String corto(String s) {
        String t = s == null ? "" : s.trim().replaceAll("\\s+", " ");
        return t.length() > 40 ? t.substring(0, 40).trim() : t;
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
