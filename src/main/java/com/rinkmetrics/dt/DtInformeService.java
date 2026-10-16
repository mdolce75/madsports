package com.rinkmetrics.dt;

import com.rinkmetrics.account.AccountController;
import com.rinkmetrics.account.EquipoDt;
import com.rinkmetrics.account.Usuario;
import com.rinkmetrics.catalog.CatalogService;
import com.rinkmetrics.player.RosterService;
import com.rinkmetrics.player.RosterService.PlantelJugador;
import com.rinkmetrics.report.PdfRenderer;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Informes del DT: el de un partido (con sus eventos) y los de los partidos finalizados de un período (mes, bimestre,
 * semestre o campeonato), del equipo o de un jugador; en JSON y en PDF (plantilla {@code templates/dt-informe.html}).
 */
@Service
public class DtInformeService {

    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /** Un PDF listo para descargar. */
    public record Pdf(String archivo, byte[] contenido) {
    }

    private final PartidoDtRepository partidos;
    private final AccionDtRepository acciones;
    private final RosterService roster;
    private final CatalogService catalog;
    private final PdfRenderer pdf;
    private final Clock clock;

    public DtInformeService(PartidoDtRepository partidos, AccionDtRepository acciones, RosterService roster,
                            CatalogService catalog, PdfRenderer pdf, Clock clock) {
        this.partidos = partidos;
        this.acciones = acciones;
        this.roster = roster;
        this.catalog = catalog;
        this.pdf = pdf;
        this.clock = clock;
    }

    static boolean finalizado(PartidoDt p) {
        return p.getEstado() == EstadoPartido.TERMINADO || p.getEstado() == EstadoPartido.CERRADO;
    }

    /** Informe de un partido del DT (en cualquier estado: también sirve durante el partido). */
    public InformeDt partido(Usuario u, Long id, String jugador) {
        PartidoDt p = partidos.findByIdAndUsuarioId(id, u.getId())
                .orElseThrow(() -> new DtService.NoEncontrado("No existe ese partido."));
        String titulo = u.getClubNombre() + " " + p.getGolesFavor() + " - " + p.getGolesContra() + " " + p.getRival();
        String detalle = String.join(" · ", Stream.of(nombre(p.getCategoria(), p.getRama()),
                p.isLocal() ? "Local" : "Visitante", p.getTorneo(), p.getFecha().format(FECHA))
                .filter(s -> s != null && !s.isBlank()).toList());
        return armar(u, titulo, detalle, true, List.of(p), jugador);
    }

    /** Informe de los partidos finalizados del equipo en el período. */
    public InformeDt periodo(Usuario u, String categoria, String rama, Periodo periodo, String valor, String jugador) {
        if (periodo == null) {
            throw new IllegalArgumentException("Elegí el período del informe.");
        }
        EquipoDt e = DtService.equipo(u, categoria, rama);
        String v = valor == null ? "" : valor.trim();
        String titulo = periodo.nombre(v);
        List<PartidoDt> ps = partidos.findByUsuarioIdOrderByFechaDescIdDesc(u.getId()).stream()
                .filter(p -> p.getCategoria().equals(e.categoria()) && p.getRama().equals(e.rama()))
                .filter(DtInformeService::finalizado)
                .filter(p -> periodo.valor(p.getFecha(), p.getTorneo()).equals(v))
                .toList().reversed();
        if (ps.isEmpty()) {
            throw new IllegalArgumentException("No hay partidos finalizados en " + titulo + ".");
        }
        String detalle = nombre(e.categoria(), e.rama()) + " · " + ps.size() + (ps.size() == 1 ? " partido" : " partidos");
        return armar(u, periodo == Periodo.CAMPEONATO && !v.isEmpty() ? "Campeonato " + titulo : titulo, detalle,
                false, ps, jugador);
    }

    public Pdf pdf(InformeDt informe) {
        String nombre = informe.unPartido()
                ? "informe-" + slug(informe.partidos().getFirst().rival()) + "-" + informe.partidos().getFirst().fecha()
                : "informe-" + slug(informe.titulo());
        if (informe.jugador() != null) {
            nombre += "-" + slug(informe.jugador().nombre());
        }
        return new Pdf(nombre + ".pdf", pdf.templatePdf("dt-informe", Map.of("r", informe)));
    }

    public String html(InformeDt informe) {
        return pdf.templateHtml("dt-informe", Map.of("r", informe));
    }

    private InformeDt armar(Usuario u, String titulo, String detalle, boolean unPartido, List<PartidoDt> ps,
                            String jugador) {
        List<AccionDt> lista = acciones.findByPartidoIdInOrderByPartidoIdAscTiempoAscSegundoAscCreadaAsc(
                ps.stream().map(PartidoDt::getId).toList()).stream().filter(AccionDt::activa).toList();
        PartidoDt primero = ps.getFirst();
        return InformeDt.armar(titulo, detalle, u.getClubNombre(), nombre(primero.getCategoria(), primero.getRama()),
                LocalDate.now(clock), unPartido, ps, lista, plantel(u, primero), jugador);
    }

    /** Plantel del equipo para el número y los arqueros; si SIDGAD no responde, el informe sale sin números. */
    private Map<Integer, PlantelJugador> plantel(Usuario u, PartidoDt p) {
        try {
            return roster.plantel(u.federacion(), u.getClubId(), catalog.defaultYear(), p.getCategoria(), p.getRama())
                    .stream().collect(Collectors.toMap(PlantelJugador::id, Function.identity(), (a, b) -> a));
        } catch (RuntimeException e) {
            return Map.of();
        }
    }

    private static String nombre(String categoria, String rama) {
        return AccountController.equipoNombre(new EquipoDt(categoria, rama));
    }

    static String slug(String s) {
        String sin = Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        String out = sin.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return out.isEmpty() ? "dt" : out;
    }
}
