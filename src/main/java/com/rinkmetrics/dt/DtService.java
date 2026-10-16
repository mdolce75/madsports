package com.rinkmetrics.dt;

import com.rinkmetrics.account.EquipoDt;
import com.rinkmetrics.account.SuscripcionType;
import com.rinkmetrics.account.Usuario;
import com.rinkmetrics.catalog.CatalogService;
import com.rinkmetrics.player.RosterService;
import com.rinkmetrics.player.RosterService.PartidoEquipo;
import com.rinkmetrics.player.RosterService.PlantelJugador;
import com.rinkmetrics.sidgad.model.Competition;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Planilla DT en vivo: partidos que carga el DT de sus equipos, sus acciones (cada una de un jugador, con tiempo y
 * segundo) y el reloj. Todo se valida contra el DT: sus equipos, su cupo del mes y sus partidos.
 */
@Service
public class DtService {

    /** El partido no existe o no es del DT (para otro DT, es como si no existiera). */
    public static class NoEncontrado extends RuntimeException {
        public NoEncontrado(String message) {
            super(message);
        }
    }

    /** Choca con algo ya cargado (cupo del mes, partido que carga otro DT). */
    public static class Conflicto extends RuntimeException {
        public Conflicto(String message) {
            super(message);
        }
    }

    /** Duración de cada tiempo si la competición no la informa. */
    static final int MINUTOS_POR_DEFECTO = 25;
    private static final Pattern UUID = Pattern.compile("[0-9a-fA-F-]{36}");
    private static final DateTimeFormatter DIA = DateTimeFormatter.ofPattern("dd/MM");

    /**
     * Un partido del calendario del equipo para el combo de rivales.
     *
     * @param jugado  ya tiene resultado en SIDGAD
     * @param cargado id del partido de la planilla si ya se empezó a cargar (null si no)
     */
    public record Disponible(int idc, int idp, LocalDate fecha, LocalTime hora, String rival, boolean local,
                             String torneo, int minutosTiempo, boolean jugado, Long cargado) {
    }

    /** Un partido nuevo: del combo ({@code idp}) o un amistoso ({@code rival}, {@code fecha}, {@code local}). */
    public record NuevoPartido(String categoria, String rama, Integer idp, String rival, LocalDate fecha, Boolean local) {
    }

    /**
     * Una acción que manda el teléfono.
     *
     * @param id            uuid generado en el teléfono
     * @param jugadorId     id_player de SIDGAD, o 0 para un jugador agregado a mano (con {@code jugadorNombre})
     * @param segundo       segundos jugados del tiempo (25:00 menos lo que marca el reloj)
     */
    public record AccionNueva(String id, int jugadorId, String jugadorNombre, TipoAccion tipo, Resultado resultado,
                              int tiempo, int segundo) {
    }

    /** @param restante lo que marca el reloj, en segundos (cuenta hacia atrás) */
    public record RelojView(int tiempo, int jugados, int restante, boolean corriendo) {
    }

    public record AccionView(String id, int jugadorId, String jugadorNombre, TipoAccion tipo, Resultado resultado,
                             int tiempo, int segundo) {

        static AccionView of(AccionDt a) {
            return new AccionView(a.getId(), a.getJugadorId(), a.getJugadorNombre(), a.getTipo(), a.getResultado(),
                    a.getTiempo(), a.getSegundo());
        }
    }

    /** @param acciones las activas, en orden de juego (vacía en la lista de partidos) */
    public record PartidoView(Long id, String categoria, String rama, Integer idp, String torneo, String rival,
                              LocalDate fecha, boolean local, int minutosTiempo, EstadoPartido estado, int golesFavor,
                              int golesContra, RelojView reloj, List<AccionView> acciones) {
    }

    private final PartidoDtRepository partidos;
    private final AccionDtRepository acciones;
    private final RosterService roster;
    private final CatalogService catalog;
    private final Clock clock;

    public DtService(PartidoDtRepository partidos, AccionDtRepository acciones, RosterService roster,
                     CatalogService catalog, Clock clock) {
        this.partidos = partidos;
        this.acciones = acciones;
        this.roster = roster;
        this.catalog = catalog;
        this.clock = clock;
    }

    /** El equipo pedido, que tiene que ser uno de los del DT; sin categoría, el primero. */
    static EquipoDt equipo(Usuario u, String categoria, String rama) {
        List<EquipoDt> suyos = u.getEquiposDt();
        if (suyos.isEmpty()) {
            throw new IllegalArgumentException("Tu usuario no tiene equipos cargados.");
        }
        if (categoria == null || categoria.isBlank()) {
            return suyos.getFirst();
        }
        EquipoDt pedido = new EquipoDt(categoria.trim().toUpperCase(Locale.ROOT),
                rama == null ? "" : rama.trim().toUpperCase(Locale.ROOT));
        if (!suyos.contains(pedido)) {
            throw new IllegalArgumentException("Ese equipo no es uno de los tuyos.");
        }
        return pedido;
    }

    /** Combo de rivales: los partidos del equipo en el año, los próximos primero y después los jugados. */
    public List<Disponible> disponibles(Usuario u, String categoria, String rama) {
        EquipoDt e = equipo(u, categoria, rama);
        LocalDate hoy = LocalDate.now(clock);
        Map<Integer, Long> cargados = partidos.findByUsuarioIdOrderByFechaDescIdDesc(u.getId()).stream()
                .filter(p -> p.getIdp() != null && p.getCategoria().equals(e.categoria()) && p.getRama().equals(e.rama()))
                .collect(Collectors.toMap(PartidoDt::getIdp, PartidoDt::getId, (a, b) -> a));
        return partidosDelEquipo(u, e).stream()
                .map(p -> new Disponible(p.competition().id(), p.match().idp(), p.match().date(), p.match().time(),
                        p.rival(), p.local(), torneo(p.competition()), minutos(p.competition()), p.match().played(),
                        cargados.get(p.match().idp())))
                .sorted(Comparator.comparing((Disponible d) -> d.jugado() || d.fecha() != null && d.fecha().isBefore(hoy))
                        .thenComparing((a, b) -> {
                            boolean pasado = a.jugado() || a.fecha() != null && a.fecha().isBefore(hoy);
                            Comparator<LocalDate> fechas = Comparator.nullsLast(Comparator.naturalOrder());
                            return pasado ? fechas.reversed().compare(a.fecha(), b.fecha()) : fechas.compare(a.fecha(), b.fecha());
                        }))
                .toList();
    }

    /** Plantel del equipo (de sus actas), con número y arqueros marcados. */
    public List<PlantelJugador> plantel(Usuario u, String categoria, String rama) {
        EquipoDt e = equipo(u, categoria, rama);
        return roster.plantel(u.federacion(), u.getClubId(), catalog.defaultYear(), e.categoria(), e.rama());
    }

    /**
     * Empieza a cargar un partido. Si el partido de SIDGAD ya lo había empezado el DT, lo devuelve para retomarlo;
     * si lo carga otro DT del mismo equipo, lo rechaza. Cada partido nuevo cuenta para el cupo del mes.
     */
    @Transactional
    public PartidoView crear(Usuario u, NuevoPartido n) {
        EquipoDt e = equipo(u, n.categoria(), n.rama());
        if (n.idp() != null) {
            var existente = partidos.findByClubIdAndCategoriaAndRamaAndIdp(u.getClubId(), e.categoria(), e.rama(), n.idp());
            if (existente.isPresent()) {
                if (!existente.get().getUsuarioId().equals(u.getId())) {
                    throw new Conflicto("Ese partido ya lo está cargando otro DT de tu equipo.");
                }
                return view(existente.get(), true);
            }
        }
        verificarCupo(u);
        Instant ahora = clock.instant();
        PartidoDt p;
        if (n.idp() != null) {
            PartidoEquipo pe = partidosDelEquipo(u, e).stream().filter(x -> x.match().idp() == n.idp()).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Ese partido no es de tu equipo."));
            p = new PartidoDt(u.getId(), u.federacion().name(), u.getClubId(), e.categoria(), e.rama(),
                    pe.competition().id(), n.idp(), torneo(pe.competition()), pe.rival(),
                    pe.match().date() == null ? LocalDate.now(clock) : pe.match().date(), pe.local(),
                    minutos(pe.competition()), ahora);
        } else {
            if (n.rival() == null || n.rival().isBlank()) {
                throw new IllegalArgumentException("Elegí el rival del calendario o escribí el del amistoso.");
            }
            p = new PartidoDt(u.getId(), u.federacion().name(), u.getClubId(), e.categoria(), e.rama(), null, null,
                    "Amistoso", n.rival().trim(), n.fecha() == null ? LocalDate.now(clock) : n.fecha(),
                    n.local() == null || n.local(), MINUTOS_POR_DEFECTO, ahora);
        }
        return view(partidos.save(p), true);
    }

    /** Partidos cargados por el DT, los más recientes primero (sin las acciones). */
    public List<PartidoView> lista(Usuario u) {
        return partidos.findByUsuarioIdOrderByFechaDescIdDesc(u.getId()).stream().map(p -> view(p, false)).toList();
    }

    public PartidoView partido(Usuario u, Long id) {
        return view(suyo(u, id), true);
    }

    /**
     * Agrega acciones (una o una tanda de las cargadas sin señal). Una acción con un id ya guardado se ignora: el
     * teléfono puede reenviar sin duplicar.
     */
    @Transactional
    public PartidoView agregar(Usuario u, Long id, List<AccionNueva> nuevas) {
        PartidoDt p = suyo(u, id);
        if (!p.aceptaAcciones()) {
            throw new IllegalStateException("El partido está cerrado: reabrilo para cargar acciones.");
        }
        Map<Integer, PlantelJugador> plantel = null;
        Instant ahora = clock.instant();
        for (AccionNueva a : nuevas == null ? List.<AccionNueva>of() : nuevas) {
            validar(a, p);
            if (acciones.existsById(a.id())) {
                continue;
            }
            String nombre;
            if (a.jugadorId() > 0) {
                if (plantel == null) {
                    plantel = roster.plantel(u.federacion(), u.getClubId(), catalog.defaultYear(), p.getCategoria(),
                            p.getRama()).stream().collect(Collectors.toMap(PlantelJugador::id, Function.identity()));
                }
                PlantelJugador j = plantel.get(a.jugadorId());
                if (j == null) {
                    throw new IllegalArgumentException("El jugador " + a.jugadorId() + " no está en el plantel.");
                }
                nombre = j.nombre();
            } else {
                if (a.jugadorNombre() == null || a.jugadorNombre().isBlank()) {
                    throw new IllegalArgumentException("Elegí el jugador de la acción.");
                }
                nombre = a.jugadorNombre().trim();
            }
            acciones.save(new AccionDt(a.id(), p.getId(), Math.max(0, a.jugadorId()), nombre, a.tipo(), a.resultado(),
                    a.tiempo(), a.segundo(), ahora));
        }
        recalcular(p);
        return view(p, true);
    }

    /** Deshacer: la acción queda borrada y el marcador se recalcula. */
    @Transactional
    public PartidoView borrar(Usuario u, Long id, String accionId) {
        PartidoDt p = suyo(u, id);
        if (!p.aceptaAcciones()) {
            throw new IllegalStateException("El partido está cerrado: reabrilo para borrar acciones.");
        }
        AccionDt a = acciones.findById(accionId).filter(x -> x.getPartidoId().equals(p.getId()))
                .orElseThrow(() -> new NoEncontrado("No existe esa acción en el partido."));
        a.borrar(clock.instant());
        recalcular(p);
        return view(p, true);
    }

    public enum AccionReloj { INICIAR, PAUSAR, REANUDAR, FIN_TIEMPO, AJUSTAR }

    /** Iniciar (1T o 2T), pausar, reanudar, terminar el tiempo o ajustar el reloj ({@code segundos} de tiempo jugado). */
    @Transactional
    public PartidoView reloj(Usuario u, Long id, AccionReloj accion, Integer segundos) {
        PartidoDt p = suyo(u, id);
        Instant ahora = clock.instant();
        if (accion == null) {
            throw new IllegalArgumentException("Indicá la acción del reloj.");
        }
        switch (accion) {
            case INICIAR -> p.iniciar(ahora);
            case PAUSAR -> p.pausar(ahora);
            case REANUDAR -> p.reanudar(ahora);
            case FIN_TIEMPO -> p.finTiempo(ahora);
            case AJUSTAR -> p.ajustar(segundos == null ? 0 : segundos, ahora);
        }
        return view(p, true);
    }

    @Transactional
    public PartidoView cerrar(Usuario u, Long id) {
        PartidoDt p = suyo(u, id);
        p.cerrar(clock.instant());
        return view(p, true);
    }

    @Transactional
    public PartidoView reabrir(Usuario u, Long id) {
        PartidoDt p = suyo(u, id);
        p.reabrir(clock.instant());
        return view(p, true);
    }

    /**
     * El DT no puede empezar más partidos en el mes que los de su plan. El mes cuenta desde el día de su suscripción
     * (suscripto el 15: del 15 al 14 del mes siguiente).
     */
    void verificarCupo(Usuario u) {
        SuscripcionType plan = u.getSuscripcionType();
        if (plan == null || plan.getPartidosPorMes() <= 0) {
            throw new IllegalStateException("Tu plan no incluye la planilla DT.");
        }
        LocalDate hoy = LocalDate.now(clock);
        LocalDate desde = inicioDelMes(u.getSuscripcionDesde() == null ? hoy : u.getSuscripcionDesde(), hoy);
        long usados = partidos.countByUsuarioIdAndCreadoGreaterThanEqual(u.getId(),
                desde.atStartOfDay(clock.getZone()).toInstant());
        if (usados >= plan.getPartidosPorMes()) {
            throw new Conflicto("Ya cargaste los " + plan.getPartidosPorMes() + " partidos de este mes. El cupo se "
                    + "renueva el " + desde.plusMonths(1).format(DIA) + ".");
        }
    }

    /** Primer día del mes de suscripción que contiene a {@code hoy}. */
    static LocalDate inicioDelMes(LocalDate suscripcion, LocalDate hoy) {
        long meses = Math.max(0, ChronoUnit.MONTHS.between(suscripcion, hoy));
        LocalDate desde = suscripcion.plusMonths(meses);
        return desde.isAfter(hoy) ? suscripcion.plusMonths(meses - 1) : desde;
    }

    private void validar(AccionNueva a, PartidoDt p) {
        if (a == null || a.id() == null || !UUID.matcher(a.id()).matches()) {
            throw new IllegalArgumentException("Cada acción lleva su id (uuid).");
        }
        if (a.tipo() == null) {
            throw new IllegalArgumentException("Falta el tipo de la acción.");
        }
        if (!a.tipo().admite(a.resultado())) {
            throw new IllegalArgumentException("El resultado " + a.resultado() + " no corresponde a " + a.tipo() + ".");
        }
        if (a.tiempo() < 1 || a.tiempo() > 2) {
            throw new IllegalArgumentException("El tiempo es 1 o 2.");
        }
        if (a.segundo() < 0 || a.segundo() > p.duracion()) {
            throw new IllegalArgumentException("El minuto está fuera del tiempo.");
        }
    }

    /** Marcador a partir de las acciones activas. */
    private void recalcular(PartidoDt p) {
        List<AccionDt> activas = acciones.findByPartidoIdOrderByTiempoAscSegundoAscCreadaAsc(p.getId()).stream()
                .filter(AccionDt::activa).toList();
        p.marcador((int) activas.stream().filter(a -> a.getTipo().golAFavor(a.getResultado())).count(),
                (int) activas.stream().filter(a -> a.getTipo().golEnContra(a.getResultado())).count());
    }

    private PartidoDt suyo(Usuario u, Long id) {
        return partidos.findByIdAndUsuarioId(id, u.getId())
                .orElseThrow(() -> new NoEncontrado("No existe ese partido."));
    }

    private List<PartidoEquipo> partidosDelEquipo(Usuario u, EquipoDt e) {
        return roster.partidos(u.federacion(), u.getClubId(), catalog.defaultYear(), e.categoria(), e.rama());
    }

    private PartidoView view(PartidoDt p, boolean conAcciones) {
        Instant ahora = clock.instant();
        List<AccionView> lista = conAcciones && p.getId() != null
                ? acciones.findByPartidoIdOrderByTiempoAscSegundoAscCreadaAsc(p.getId()).stream()
                        .filter(AccionDt::activa).map(AccionView::of).toList()
                : List.of();
        return new PartidoView(p.getId(), p.getCategoria(), p.getRama(), p.getIdp(), p.getTorneo(), p.getRival(),
                p.getFecha(), p.isLocal(), p.getMinutosTiempo(), p.getEstado(), p.getGolesFavor(), p.getGolesContra(),
                new RelojView(p.getTiempoActual(), p.jugados(ahora), p.restante(ahora), p.corriendo()), lista);
    }

    private static String torneo(Competition c) {
        return c.torneoLabel();
    }

    private static int minutos(Competition c) {
        return c.periodMinutes() > 0 ? c.periodMinutes() : MINUTOS_POR_DEFECTO;
    }
}
