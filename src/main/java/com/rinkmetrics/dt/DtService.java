package com.rinkmetrics.dt;

import com.rinkmetrics.account.SuscripcionType;
import com.rinkmetrics.account.Usuario;
import com.rinkmetrics.dt.PlantelDtService.JugadorPlantel;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * RinkMetrics en vivo: partidos que carga el DT de sus equipos, sus acciones (cada una de un jugador, con tiempo y
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

    /** Choca con algo ya cargado (el cupo de partidos del mes). */
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
     * Un partido nuevo: equipo del DT (sin equipo, el primero), rival (escrito a mano), fecha (sin fecha, hoy), si el
     * equipo es local, el campeonato (opcional: agrupa los partidos en los informes) y los minutos de cada tiempo (sin
     * minutos, 25).
     */
    public record NuevoPartido(Long equipo, String rival, LocalDate fecha, Boolean local, String torneo,
                               Integer minutos) {
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

    /** Una acción de un partido del equipo (para las fichas de los jugadores). */
    public record AccionDeEquipo(Long partidoId, String id, int jugadorId, String jugadorNombre, TipoAccion tipo,
                                 Resultado resultado, int tiempo, int segundo) {
    }

    /** @param acciones las activas, en orden de juego (vacía en la lista de partidos) */
    /** @param equipo nombre del equipo del DT */
    public record PartidoView(Long id, Long equipoId, String equipo, Integer idp, String torneo, String rival,
                              LocalDate fecha, boolean local, int minutosTiempo, EstadoPartido estado, int golesFavor,
                              int golesContra, RelojView reloj, List<AccionView> acciones) {
    }

    private final PartidoDtRepository partidos;
    private final AccionDtRepository acciones;
    private final PlantelDtService planteles;
    private final EquipoDtService equipos;
    private final PartidoCreadoRepository creados;
    private final Clock clock;

    public DtService(PartidoDtRepository partidos, AccionDtRepository acciones, PlantelDtService planteles,
                     EquipoDtService equipos, PartidoCreadoRepository creados, Clock clock) {
        this.partidos = partidos;
        this.acciones = acciones;
        this.planteles = planteles;
        this.equipos = equipos;
        this.creados = creados;
        this.clock = clock;
    }

    /**
     * Empieza a cargar un partido. Si el DT ya tiene uno de ese equipo con el mismo rival y la misma fecha, lo devuelve
     * para retomarlo (no cuenta de nuevo para el cupo). Cada partido nuevo cuenta para el cupo del mes.
     */
    @Transactional
    public PartidoView crear(Usuario u, NuevoPartido n) {
        Equipo e = equipos.equipo(u, n.equipo());
        String rival = n.rival() == null ? "" : n.rival().trim().replaceAll("\\s+", " ");
        if (rival.isEmpty()) {
            throw new IllegalArgumentException("Escribí el rival.");
        }
        if (rival.length() > 60) {
            throw new IllegalArgumentException("El nombre del rival es muy largo.");
        }
        String torneo = n.torneo() == null ? "" : n.torneo().trim().replaceAll("\\s+", " ");
        if (torneo.length() > 60) {
            throw new IllegalArgumentException("El nombre del campeonato es muy largo.");
        }
        int minutos = n.minutos() == null ? MINUTOS_POR_DEFECTO : n.minutos();
        if (minutos < 10 || minutos > 40) {
            throw new IllegalArgumentException("Cada tiempo dura entre 10 y 40 minutos.");
        }
        LocalDate fecha = n.fecha() == null ? LocalDate.now(clock) : n.fecha();
        var existente = partidos.findByUsuarioIdAndEquipoIdAndRivalAndFecha(u.getId(), e.getId(), rival, fecha);
        if (existente.isPresent()) {
            return view(existente.get(), true);
        }
        verificarCupo(u);
        PartidoDt p = new PartidoDt(u.getId(), u.federacion().name(), u.getClubId(), e.getId(), null, null,
                torneo.isEmpty() ? null : torneo, rival, fecha, n.local() == null || n.local(), minutos, clock.instant());
        creados.save(new PartidoCreado(u.getId(), p.getCreado()));
        return view(partidos.save(p), true, e.getNombre());
    }

    /** Partidos cargados por el DT, los más recientes primero (sin las acciones). */
    public List<PartidoView> lista(Usuario u) {
        return partidos.findByUsuarioIdOrderByFechaDescIdDesc(u.getId()).stream().map(p -> view(p, false)).toList();
    }

    /**
     * Las acciones activas de todos los partidos que el DT cargó de un equipo: con eso la pantalla arma la ficha de
     * cada jugador (pentágono e índice).
     */
    public List<AccionDeEquipo> accionesDelEquipo(Usuario u, Long equipoId) {
        Equipo e = equipos.equipo(u, equipoId);
        List<Long> ids = partidos.findByUsuarioIdOrderByFechaDescIdDesc(u.getId()).stream()
                .filter(p -> p.getEquipoId().equals(e.getId()))
                .map(PartidoDt::getId).toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        return acciones.findByPartidoIdInOrderByPartidoIdAscTiempoAscSegundoAscCreadaAsc(ids).stream()
                .filter(AccionDt::activa)
                .map(a -> new AccionDeEquipo(a.getPartidoId(), a.getId(), a.getJugadorId(), a.getJugadorNombre(),
                        a.getTipo(), a.getResultado(), a.getTiempo(), a.getSegundo()))
                .toList();
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
        Map<Integer, JugadorPlantel> plantel = null;
        Instant ahora = clock.instant();
        for (AccionNueva a : nuevas == null ? List.<AccionNueva>of() : nuevas) {
            validar(a, p);
            if (acciones.existsById(a.id())) {
                continue;
            }
            String nombre;
            if (a.jugadorId() > 0) {
                if (plantel == null) {
                    plantel = planteles.plantel(u, p.getEquipoId()).stream()
                            .collect(Collectors.toMap(JugadorPlantel::id, Function.identity()));
                }
                JugadorPlantel j = plantel.get(a.jugadorId());
                if (j == null) {
                    throw new IllegalArgumentException("El jugador " + a.jugadorId() + " no está en el plantel del equipo.");
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

    /**
     * Elimina el partido y todas sus acciones (la base las borra en cascada): deja de contar en las estadísticas, las
     * fichas y los informes, pero sigue contando para el cupo del mes. No se puede deshacer.
     */
    @Transactional
    public void eliminar(Usuario u, Long id) {
        partidos.delete(suyo(u, id));
    }

    @Transactional
    public PartidoView reabrir(Usuario u, Long id) {
        PartidoDt p = suyo(u, id);
        p.reabrir(clock.instant());
        return view(p, true);
    }

    /**
     * El DT no puede empezar más partidos en el mes que los de su plan (0 = sin límite), ni después del último día de
     * un plan con fecha fija (el free, hasta el 31/12/2026). El mes cuenta desde el día de su suscripción (suscripto
     * el 15: del 15 al 14 del mes siguiente).
     */
    void verificarCupo(Usuario u) {
        SuscripcionType plan = u.getSuscripcionType();
        if (plan == null || !"DT".equals(plan.getTipoUsuario())) {
            throw new IllegalStateException("Tu plan no incluye RinkMetrics.");
        }
        LocalDate hoy = LocalDate.now(clock);
        if (!plan.vigente(hoy)) {
            throw new Conflicto("Tu plan " + plan.getNombre() + " terminó el "
                    + plan.getVigenteHasta().format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
                    + ". Pasate al plan mensual para seguir cargando partidos.");
        }
        if (plan.getPartidosPorMes() <= 0) {
            return;
        }
        LocalDate desde = inicioDelMes(u.getSuscripcionDesde() == null ? hoy : u.getSuscripcionDesde(), hoy);
        long usados = creados.countByUsuarioIdAndCreadoGreaterThanEqual(u.getId(),
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

    /** Vista de un partido leído con su equipo. */
    private PartidoView view(PartidoDt p, boolean conAcciones) {
        return view(p, conAcciones, p.getEquipo() == null ? "" : p.getEquipo().getNombre());
    }

    private PartidoView view(PartidoDt p, boolean conAcciones, String equipo) {
        Instant ahora = clock.instant();
        List<AccionView> lista = conAcciones && p.getId() != null
                ? acciones.findByPartidoIdOrderByTiempoAscSegundoAscCreadaAsc(p.getId()).stream()
                        .filter(AccionDt::activa).map(AccionView::of).toList()
                : List.of();
        return new PartidoView(p.getId(), p.getEquipoId(), equipo, p.getIdp(),
                p.getTorneo(), p.getRival(),
                p.getFecha(), p.isLocal(), p.getMinutosTiempo(), p.getEstado(), p.getGolesFavor(), p.getGolesContra(),
                new RelojView(p.getTiempoActual(), p.jugados(ahora), p.restante(ahora), p.corriendo()), lista);
    }
}
