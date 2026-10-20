package com.rinkmetrics.dt;

import com.rinkmetrics.account.SuscripcionType;
import com.rinkmetrics.account.Usuario;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Equipos del DT (el ABM de equipos de la sección Equipo): un nombre libre, hasta los que permite su plan (free 1,
 * mensual 3). Cada partido y cada jugador son de un equipo; un equipo con partidos no se puede borrar.
 */
@Service
public class EquipoDtService {

    /**
     * Un equipo con su balance: partidos finalizados jugados, ganados, empatados y perdidos.
     *
     * @param categoriaSidgad y {@code ramaSidgad}: la categoría del club de la que salió el plantel (null: nombre libre)
     */
    public record EquipoView(Long id, String nombre, int jugados, int ganados, int empatados, int perdidos,
                             String categoriaSidgad, String ramaSidgad) {
    }

    private final EquipoRepository equipos;
    private final PartidoDtRepository partidos;
    private final JugadorDtRepository jugadores;
    private final Clock clock;

    public EquipoDtService(EquipoRepository equipos, PartidoDtRepository partidos, JugadorDtRepository jugadores,
                           Clock clock) {
        this.equipos = equipos;
        this.partidos = partidos;
        this.jugadores = jugadores;
        this.clock = clock;
    }

    public List<EquipoView> lista(Usuario u) {
        Map<Long, List<PartidoDt>> porEquipo = partidos.findByUsuarioIdOrderByFechaDescIdDesc(u.getId()).stream()
                .filter(DtInformeService::finalizado)
                .collect(Collectors.groupingBy(PartidoDt::getEquipoId));
        return equipos.findByUsuarioIdOrderByIdAsc(u.getId()).stream().map(e -> {
            List<PartidoDt> ps = porEquipo.getOrDefault(e.getId(), List.of());
            return new EquipoView(e.getId(), e.getNombre(), ps.size(),
                    (int) ps.stream().filter(p -> p.getGolesFavor() > p.getGolesContra()).count(),
                    (int) ps.stream().filter(p -> p.getGolesFavor() == p.getGolesContra()).count(),
                    (int) ps.stream().filter(p -> p.getGolesFavor() < p.getGolesContra()).count(),
                    e.getCategoriaSidgad(), e.getRamaSidgad());
        }).toList();
    }

    /** El equipo pedido, que tiene que ser del DT; sin id, el primero. */
    public Equipo equipo(Usuario u, Long id) {
        if (id == null) {
            return equipos.findByUsuarioIdOrderByIdAsc(u.getId()).stream().findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Primero creá un equipo en la sección Equipo."));
        }
        return equipos.findByIdAndUsuarioId(id, u.getId())
                .orElseThrow(() -> new DtService.NoEncontrado("No existe ese equipo."));
    }

    @Transactional
    public EquipoView crear(Usuario u, String nombre) {
        Equipo e = nuevo(u, nombre, null, null);
        return new EquipoView(e.getId(), e.getNombre(), 0, 0, 0, 0, null, null);
    }

    /**
     * Crea el equipo (con su nombre validado y dentro del cupo del plan). Con {@code categoria} y {@code rama} queda
     * anotado de qué categoría del club en SIDGAD sale su plantel.
     */
    @Transactional
    public Equipo nuevo(Usuario u, String nombre, String categoria, String rama) {
        String n = nombre(u, nombre, null);
        SuscripcionType plan = u.getSuscripcionType();
        int max = plan == null ? 0 : plan.getEquiposMax();
        if (max > 0 && equipos.findByUsuarioIdOrderByIdAsc(u.getId()).size() >= max) {
            throw new DtService.Conflicto("Tu plan " + plan.getNombre() + " permite " + (max == 1 ? "1 equipo" : "hasta " + max
                    + " equipos") + ". Para sumar otro, borrá uno sin partidos"
                    + ("DT_FREE".equals(plan.getCodigo()) ? " o pasate al plan mensual." : "."));
        }
        return equipos.save(new Equipo(u.getId(), n, categoria, rama, clock.instant()));
    }

    @Transactional
    public EquipoView renombrar(Usuario u, Long id, String nombre) {
        Equipo e = equipo(u, id);
        e.renombrar(nombre(u, nombre, e));
        return lista(u).stream().filter(v -> v.id().equals(id)).findFirst().orElseThrow();
    }

    /** Borra un equipo sin partidos; sus jugadores siguen en los otros equipos (o quedan sin equipo). */
    @Transactional
    public void borrar(Usuario u, Long id) {
        Equipo e = equipo(u, id);
        long n = partidos.countByEquipoId(e.getId());
        if (n > 0) {
            throw new DtService.Conflicto(e.getNombre() + " tiene " + n + (n == 1 ? " partido cargado" : " partidos cargados")
                    + ": no se puede borrar.");
        }
        jugadores.findByUsuarioIdAndActivoTrue(u.getId()).forEach(j -> j.sacarDe(e.getId()));
        equipos.delete(e);
    }

    private String nombre(Usuario u, String nombre, Equipo actual) {
        String n = nombre == null ? "" : nombre.trim().replaceAll("\\s+", " ");
        if (n.isEmpty()) {
            throw new IllegalArgumentException("Escribí el nombre del equipo.");
        }
        if (n.length() > 60) {
            throw new IllegalArgumentException("El nombre del equipo es muy largo (hasta 60 letras).");
        }
        boolean mismo = actual != null && actual.getNombre().equalsIgnoreCase(n);
        if (!mismo && equipos.existsByUsuarioIdAndNombreIgnoreCase(u.getId(), n)) {
            throw new DtService.Conflicto("Ya tenés un equipo llamado " + n + ".");
        }
        return n;
    }
}
