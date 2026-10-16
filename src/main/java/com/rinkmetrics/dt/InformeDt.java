package com.rinkmetrics.dt;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.rinkmetrics.dt.PlantelDtService.JugadorPlantel;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Informe del DT: de un partido o de varios (un mes, un bimestre, un semestre o un campeonato). Lleva las métricas del
 * equipo, las de cada jugador y, en el de un partido, la lista de eventos. Con un jugador elegido, todo es de ese
 * jugador. La pantalla arma lo mismo en {@code js/dt-informe.js} (para mostrarlo en vivo y sin señal); este es el del
 * PDF.
 *
 * @param titulo    "VELEZ 3 - 2 HURACAN" o "Octubre 2026"
 * @param detalle   equipo, condición, campeonato y fecha; o equipo y cantidad de partidos
 * @param jugador   el jugador elegido (null: todo el equipo)
 * @param eventos   solo en el informe de un partido
 */
public record InformeDt(String titulo, String detalle, String club, String equipo, LocalDate generado,
                        boolean unPartido, List<PartidoFila> partidos, Balance balance, Metricas totales,
                        List<JugadorFila> jugadores, JugadorFila jugador, List<Evento> eventos) {

    /** Un partido del informe. @param resultado G, E o P */
    public record PartidoFila(Long id, LocalDate fecha, String rival, boolean local, String torneo, int golesFavor,
                              int golesContra, String resultado) {

        static PartidoFila of(PartidoDt p) {
            int gf = p.getGolesFavor(), gc = p.getGolesContra();
            return new PartidoFila(p.getId(), p.getFecha(), p.getRival(), p.isLocal(), p.getTorneo(), gf, gc,
                    gf > gc ? "G" : gf == gc ? "E" : "P");
        }
    }

    /** Partidos jugados, ganados, empatados y perdidos, y los goles. */
    public record Balance(int jugados, int ganados, int empatados, int perdidos, int golesFavor, int golesContra) {

        static Balance de(List<PartidoFila> ps) {
            return new Balance(ps.size(), (int) ps.stream().filter(p -> p.resultado().equals("G")).count(),
                    (int) ps.stream().filter(p -> p.resultado().equals("E")).count(),
                    (int) ps.stream().filter(p -> p.resultado().equals("P")).count(),
                    ps.stream().mapToInt(PartidoFila::golesFavor).sum(),
                    ps.stream().mapToInt(PartidoFila::golesContra).sum());
        }
    }

    /**
     * Las métricas del informe. Goles: tiros, libres y penales convertidos. Goles recibidos y atajadas (con libres y
     * penales atajados): del arquero. Pérdidas y recuperaciones: en defensa (campo propio) y en ataque (campo rival).
     */
    public record Metricas(int goles, int golesRecibidos, int tiros, int tirosGol, int atajadas, int perdidasDefensa,
                           int perdidasAtaque, int recuperacionesDefensa, int recuperacionesAtaque,
                           int faltasRecibidas, int faltasCometidas, int azules, int amarillas, int rojas,
                           int penales, int penalesGol, int libres, int libresGol) {

        static Metricas de(Collection<AccionDt> acc) {
            return new Metricas(
                    (int) acc.stream().filter(a -> a.getTipo().golAFavor(a.getResultado())).count(),
                    (int) acc.stream().filter(a -> a.getTipo().golEnContra(a.getResultado())).count(),
                    n(acc, TipoAccion.TIRO, null), n(acc, TipoAccion.TIRO, Resultado.GOL),
                    n(acc, TipoAccion.ATAJADA, null) + n(acc, TipoAccion.LIBRE_CONTRA, Resultado.ATAJADO)
                            + n(acc, TipoAccion.PENAL_CONTRA, Resultado.ATAJADO),
                    n(acc, TipoAccion.PERDIDA, Resultado.PROPIO), n(acc, TipoAccion.PERDIDA, Resultado.RIVAL),
                    n(acc, TipoAccion.RECUPERACION, Resultado.PROPIO), n(acc, TipoAccion.RECUPERACION, Resultado.RIVAL),
                    n(acc, TipoAccion.FALTA, Resultado.RECIBIDA), n(acc, TipoAccion.FALTA, Resultado.COMETIDA),
                    n(acc, TipoAccion.TARJETA, Resultado.AZUL), n(acc, TipoAccion.TARJETA, Resultado.AMARILLA),
                    n(acc, TipoAccion.TARJETA, Resultado.ROJA),
                    n(acc, TipoAccion.PENAL_FAVOR, null), n(acc, TipoAccion.PENAL_FAVOR, Resultado.GOL),
                    n(acc, TipoAccion.LIBRE_FAVOR, null), n(acc, TipoAccion.LIBRE_FAVOR, Resultado.GOL));
        }

        private static int n(Collection<AccionDt> acc, TipoAccion tipo, Resultado res) {
            return (int) acc.stream().filter(a -> a.getTipo() == tipo && (res == null || a.getResultado() == res))
                    .count();
        }

        @JsonProperty
        public int perdidas() {
            return perdidasDefensa + perdidasAtaque;
        }

        @JsonProperty
        public int recuperaciones() {
            return recuperacionesDefensa + recuperacionesAtaque;
        }

        /** Porcentaje de tiros convertidos (null sin tiros). */
        @JsonProperty
        public Integer efectividadTiros() {
            return pct(tirosGol, tiros);
        }

        @JsonProperty
        public Integer efectividadPenales() {
            return pct(penalesGol, penales);
        }

        @JsonProperty
        public Integer efectividadLibres() {
            return pct(libresGol, libres);
        }

        private static Integer pct(int a, int b) {
            return b == 0 ? null : (int) Math.round(100.0 * a / b);
        }
    }

    /**
     * Un jugador del informe. @param clave "j" + id de SIDGAD, o "m" + nombre para un jugador agregado a mano
     *
     * @param partidos partidos en los que tiene acciones
     */
    public record JugadorFila(String clave, String numero, String nombre, boolean arquero, int partidos,
                              Metricas metricas) {
    }

    /** Un evento del partido. @param minuto tiempo jugado del período ("1T 12:18") */
    public record Evento(int tiempo, int segundo, String minuto, String clave, String numero, String jugador,
                         String accion, String color) {
    }

    static final Set<TipoAccion> DEL_ARCO = Set.of(TipoAccion.ATAJADA, TipoAccion.GOL_RECIBIDO,
            TipoAccion.LIBRE_CONTRA, TipoAccion.PENAL_CONTRA);

    static String clave(AccionDt a) {
        return a.getJugadorId() > 0 ? "j" + a.getJugadorId() : "m" + a.getJugadorNombre();
    }

    /**
     * Arma el informe.
     *
     * @param ps       los partidos (uno solo en el informe de un partido)
     * @param acciones las activas de esos partidos, en orden de juego
     * @param plantel  para el nombre actual, el número y si es arquero (los dados de baja no están)
     * @param clave    el jugador elegido, o null
     */
    static InformeDt armar(String titulo, String detalle, String club, String equipo, LocalDate generado,
                           boolean unPartido, List<PartidoDt> ps, List<AccionDt> acciones,
                           Map<Integer, JugadorPlantel> plantel, String clave) {
        Map<String, List<AccionDt>> porJugador = new LinkedHashMap<>();
        for (AccionDt a : acciones) {
            porJugador.computeIfAbsent(clave(a), k -> new ArrayList<>()).add(a);
        }
        List<JugadorFila> jugadores = new ArrayList<>();
        for (var e : porJugador.entrySet()) {
            AccionDt una = e.getValue().getFirst();
            JugadorPlantel pj = una.getJugadorId() > 0 ? plantel.get(una.getJugadorId()) : null;
            boolean arquero = pj != null ? pj.arquero() : e.getValue().stream().anyMatch(a -> DEL_ARCO.contains(a.getTipo()));
            jugadores.add(new JugadorFila(e.getKey(), pj == null ? "" : pj.numero(),
                    pj == null ? una.getJugadorNombre() : pj.nombre(), arquero,
                    (int) e.getValue().stream().map(AccionDt::getPartidoId).distinct().count(),
                    Metricas.de(e.getValue())));
        }
        jugadores.sort(Comparator.comparingInt((JugadorFila j) -> numero(j.numero()))
                .thenComparing(JugadorFila::nombre, String.CASE_INSENSITIVE_ORDER));

        JugadorFila elegido = null;
        List<AccionDt> lasDel = acciones;
        if (clave != null && !clave.isBlank()) {
            elegido = jugadores.stream().filter(j -> j.clave().equals(clave)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Ese jugador no tiene acciones en el informe."));
            lasDel = porJugador.get(clave);
            jugadores = List.of(elegido);
        }
        Map<String, JugadorFila> porClave = jugadores.stream().collect(Collectors.toMap(JugadorFila::clave,
                j -> j, (a, b) -> a));
        int duracion = ps.isEmpty() ? DtService.MINUTOS_POR_DEFECTO * 60 : ps.getFirst().duracion();
        List<Evento> eventos = !unPartido ? List.of() : lasDel.stream().map(a -> {
            var n = nombre(a.getTipo(), a.getResultado());
            return new Evento(a.getTiempo(), a.getSegundo(), a.getTiempo() + "T " + mmss(Math.min(a.getSegundo(), duracion)),
                    clave(a), porClave.containsKey(clave(a)) ? porClave.get(clave(a)).numero() : "",
                    porClave.containsKey(clave(a)) ? porClave.get(clave(a)).nombre() : a.getJugadorNombre(), n[0], n[1]);
        }).toList();
        List<PartidoFila> filas = ps.stream().map(PartidoFila::of).toList();
        return new InformeDt(titulo, detalle, club, equipo, generado, unPartido, filas, Balance.de(filas),
                Metricas.de(lasDel), jugadores, elegido, eventos);
    }

    private static int numero(String n) {
        try {
            return Integer.parseInt(n);
        } catch (NumberFormatException e) {
            return 999;
        }
    }

    static String mmss(int s) {
        return String.format(Locale.ROOT, "%02d:%02d", s / 60, s % 60);
    }

    /** Nombre completo de la acción y su color, como en los botones de la pantalla de carga. */
    static String[] nombre(TipoAccion tipo, Resultado res) {
        return switch (tipo) {
            case PERDIDA -> res == Resultado.PROPIO ? a("Pérdida en defensa", "#b91c1c") : a("Pérdida en ataque", "#c2410c");
            case RECUPERACION -> res == Resultado.PROPIO ? a("Recuperación en defensa", "#15803d")
                    : a("Recuperación en ataque", "#4d7c0f");
            case TIRO -> res == Resultado.GOL ? a("Tiro al arco: Gol", "#0f766e") : a("Tiro al arco: No gol", "#52525b");
            case ATAJADA -> a("Atajada", "#1d4ed8");
            case GOL_RECIBIDO -> a("Gol recibido", "#9f1239");
            case LIBRE_FAVOR -> res == Resultado.GOL ? a("Libre a favor: Gol", "#0f766e") : a("Libre a favor: No gol", "#52525b");
            case LIBRE_CONTRA -> res == Resultado.GOL ? a("Libre en contra: Gol", "#9f1239")
                    : a("Libre en contra: Atajado", "#1d4ed8");
            case PENAL_FAVOR -> res == Resultado.GOL ? a("Penal a favor: Gol", "#0f766e") : a("Penal a favor: No gol", "#52525b");
            case PENAL_CONTRA -> res == Resultado.GOL ? a("Penal en contra: Gol", "#9f1239")
                    : a("Penal en contra: Atajado", "#1d4ed8");
            case FALTA -> res == Resultado.COMETIDA ? a("Falta cometida", "#6d28d9") : a("Falta recibida", "#0369a1");
            case TARJETA -> switch (res) {
                case AZUL -> a("Tarjeta azul", "#1d4ed8");
                case ROJA -> a("Tarjeta roja", "#dc2626");
                default -> a("Tarjeta amarilla", "#facc15");
            };
        };
    }

    private static String[] a(String nombre, String color) {
        return new String[]{nombre, color};
    }
}
