package com.rinkmetrics.dt;

import com.rinkmetrics.account.Usuario;
import com.rinkmetrics.account.UsuarioRepository;
import com.rinkmetrics.dt.DtService.AccionNueva;
import com.rinkmetrics.dt.DtService.AccionReloj;
import com.rinkmetrics.dt.DtService.NuevoPartido;
import com.rinkmetrics.dt.DtService.PartidoView;
import com.rinkmetrics.dt.EquipoDtService.EquipoView;
import com.rinkmetrics.dt.PlantelDtService.CategoriaClub;
import com.rinkmetrics.dt.PlantelDtService.DatosJugador;
import com.rinkmetrics.dt.PlantelDtService.JugadorPlantel;
import com.rinkmetrics.dt.PlantelDtService.JugadorSidgad;
import com.rinkmetrics.dt.PlantelDtService.NuevoEquipo;
import com.rinkmetrics.sidgad.SidgadClient.SidgadException;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;

/**
 * RinkMetrics en vivo (perfiles DTFPP y DTAMP): equipos y jugadores (ABM), partidos, acciones, reloj e informes. Cada
 * DT ve solo sus equipos, sus jugadores y sus partidos; los de otro DT responden 404.
 */
@RestController
@RequestMapping("/api/dt")
public class DtController {

    /** @param segundos solo para AJUSTAR: segundos de tiempo jugado a sumar (o restar, si es negativo) */
    public record RelojRequest(AccionReloj accion, Integer segundos) {
    }

    public record EquipoRequest(String nombre) {
    }

    private final DtService dt;
    private final DtInformeService informes;
    private final PlantelDtService planteles;
    private final EquipoDtService equipos;
    private final UsuarioRepository usuarios;

    public DtController(DtService dt, DtInformeService informes, PlantelDtService planteles, EquipoDtService equipos,
                        UsuarioRepository usuarios) {
        this.dt = dt;
        this.informes = informes;
        this.planteles = planteles;
        this.equipos = equipos;
        this.usuarios = usuarios;
    }

    /** Equipos del DT, con su balance de partidos finalizados (jugados, ganados, empatados y perdidos). */
    @GetMapping("/equipos")
    public List<EquipoView> equipos(Authentication auth) {
        return equipos.lista(usuario(auth));
    }

    /**
     * Equipo nuevo: con nombre libre, o desde una categoría del club ({@code categoria} y {@code rama}) con su plantel
     * de SIDGAD, menos los {@code excluidos}, que quedan como "no incluidos".
     */
    @PostMapping("/equipos")
    public ResponseEntity<EquipoView> crearEquipo(@RequestBody NuevoEquipo req, Authentication auth) {
        return ResponseEntity.status(HttpStatus.CREATED).body(planteles.crearEquipo(usuario(auth), req));
    }

    /** Las categorías del club del DT en SIDGAD, para armar un equipo con su plantel. */
    @GetMapping("/club/categorias")
    public List<CategoriaClub> categoriasDelClub(Authentication auth) {
        return planteles.categoriasDelClub(usuario(auth));
    }

    /** El plantel de SIDGAD de una categoría del club (los jugadores de las actas del equipo). */
    @GetMapping("/club/plantel")
    public List<JugadorSidgad> plantelSidgad(@RequestParam String categoria, @RequestParam String rama,
                                             Authentication auth) {
        return planteles.plantelSidgad(usuario(auth), categoria, rama);
    }

    @PutMapping("/equipos/{id}")
    public EquipoView renombrarEquipo(@PathVariable Long id, @RequestBody EquipoRequest req, Authentication auth) {
        return equipos.renombrar(usuario(auth), id, req.nombre());
    }

    /** Solo un equipo sin partidos (409 si tiene). */
    @DeleteMapping("/equipos/{id}")
    public ResponseEntity<Void> borrarEquipo(@PathVariable Long id, Authentication auth) {
        equipos.borrar(usuario(auth), id);
        return ResponseEntity.noContent().build();
    }

    /** Todos los jugadores del DT, con los equipos en que juega cada uno. */
    @GetMapping("/jugadores")
    public List<JugadorPlantel> jugadores(Authentication auth) {
        return planteles.jugadores(usuario(auth));
    }

    @PostMapping("/jugadores")
    public ResponseEntity<JugadorPlantel> altaJugador(@RequestBody DatosJugador req, Authentication auth) {
        return ResponseEntity.status(HttpStatus.CREATED).body(planteles.alta(usuario(auth), req));
    }

    @PutMapping("/jugadores/{jugadorId}")
    public JugadorPlantel modificarJugador(@PathVariable int jugadorId, @RequestBody DatosJugador req,
                                           Authentication auth) {
        return planteles.modificar(usuario(auth), jugadorId, req);
    }

    /** Suma al equipo a un jugador que figuraba como "no incluido" (409 si su número ya es de otro del equipo). */
    @PostMapping("/jugadores/{jugadorId}/equipos/{equipoId}")
    public JugadorPlantel incluirJugador(@PathVariable int jugadorId, @PathVariable Long equipoId, Authentication auth) {
        return planteles.incluir(usuario(auth), jugadorId, equipoId);
    }

    @DeleteMapping("/jugadores/{jugadorId}")
    public ResponseEntity<Void> bajaJugador(@PathVariable int jugadorId, Authentication auth) {
        planteles.baja(usuario(auth), jugadorId);
        return ResponseEntity.noContent().build();
    }

    /** Plantel de un equipo (sin equipo, el primero): sus jugadores activos, por número. */
    @GetMapping("/plantel")
    public List<JugadorPlantel> plantel(@RequestParam(required = false) Long equipo, Authentication auth) {
        return planteles.plantel(usuario(auth), equipo);
    }

    /** Acciones de todos los partidos del equipo, para las estadísticas y las fichas de los jugadores. */
    @GetMapping("/acciones")
    public List<DtService.AccionDeEquipo> acciones(@RequestParam(required = false) Long equipo, Authentication auth) {
        return dt.accionesDelEquipo(usuario(auth), equipo);
    }

    @PostMapping("/partidos")
    public ResponseEntity<PartidoView> crear(@RequestBody NuevoPartido req, Authentication auth) {
        return ResponseEntity.status(HttpStatus.CREATED).body(dt.crear(usuario(auth), req));
    }

    @GetMapping("/partidos")
    public List<PartidoView> lista(Authentication auth) {
        return dt.lista(usuario(auth));
    }

    @GetMapping("/partidos/{id}")
    public PartidoView partido(@PathVariable Long id, Authentication auth) {
        return dt.partido(usuario(auth), id);
    }

    @PostMapping("/partidos/{id}/acciones")
    public PartidoView acciones(@PathVariable Long id, @RequestBody List<AccionNueva> acciones, Authentication auth) {
        return dt.agregar(usuario(auth), id, acciones);
    }

    @DeleteMapping("/partidos/{id}/acciones/{accionId}")
    public PartidoView borrar(@PathVariable Long id, @PathVariable String accionId, Authentication auth) {
        return dt.borrar(usuario(auth), id, accionId);
    }

    @PostMapping("/partidos/{id}/reloj")
    public PartidoView reloj(@PathVariable Long id, @RequestBody RelojRequest req, Authentication auth) {
        return dt.reloj(usuario(auth), id, req.accion(), req.segundos());
    }

    @PostMapping("/partidos/{id}/cerrar")
    public PartidoView cerrar(@PathVariable Long id, Authentication auth) {
        return dt.cerrar(usuario(auth), id);
    }

    /** Elimina el partido y todas sus acciones (también de las estadísticas y los informes). */
    @DeleteMapping("/partidos/{id}")
    public ResponseEntity<Void> eliminar(@PathVariable Long id, Authentication auth) {
        dt.eliminar(usuario(auth), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/partidos/{id}/reabrir")
    public PartidoView reabrir(@PathVariable Long id, Authentication auth) {
        return dt.reabrir(usuario(auth), id);
    }

    /** Informe del partido: del equipo o, con {@code jugador} ("j" + id, o "m" + nombre), de ese jugador. */
    @GetMapping("/partidos/{id}/informe")
    public InformeDt informe(@PathVariable Long id, @RequestParam(required = false) String jugador,
                             Authentication auth) {
        return informes.partido(usuario(auth), id, jugador);
    }

    @GetMapping("/partidos/{id}/informe.pdf")
    public ResponseEntity<byte[]> informePdf(@PathVariable Long id, @RequestParam(required = false) String jugador,
                                             Authentication auth) {
        return pdf(informes.pdf(informes.partido(usuario(auth), id, jugador)));
    }

    /** Informe de los partidos finalizados del equipo en un mes, bimestre, semestre o campeonato. */
    @GetMapping("/informes")
    public InformeDt informePeriodo(@RequestParam(required = false) Long equipo, @RequestParam Periodo periodo,
                                    @RequestParam(required = false) String valor,
                                    @RequestParam(required = false) String jugador, Authentication auth) {
        return informes.periodo(usuario(auth), equipo, periodo, valor, jugador);
    }

    @GetMapping("/informes.pdf")
    public ResponseEntity<byte[]> informePeriodoPdf(@RequestParam(required = false) Long equipo,
                                                    @RequestParam Periodo periodo,
                                                    @RequestParam(required = false) String valor,
                                                    @RequestParam(required = false) String jugador,
                                                    Authentication auth) {
        return pdf(informes.pdf(informes.periodo(usuario(auth), equipo, periodo, valor, jugador)));
    }

    /** El PDF se abre en el navegador (inline); desde ahí se guarda o se comparte. */
    private static ResponseEntity<byte[]> pdf(DtInformeService.Pdf p) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(p.archivo()).build()
                        .toString())
                .body(p.contenido());
    }

    private Usuario usuario(Authentication auth) {
        return usuarios.findByUsuario(auth.getName()).filter(Usuario::esDt)
                .orElseThrow(() -> new AccessDeniedException("Tu usuario no es DT."));
    }

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
    ProblemDetail badRequest(Exception e) {
        String detail = e instanceof IllegalArgumentException ? e.getMessage() : "El pedido no es válido.";
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
    }

    @ExceptionHandler(DtService.NoEncontrado.class)
    ProblemDetail notFound(DtService.NoEncontrado e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler({DtService.Conflicto.class, IllegalStateException.class})
    ProblemDetail conflict(RuntimeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(SidgadException.class)
    ProblemDetail sidgad(SidgadException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, "No se pudo leer SIDGAD: " + e.getMessage());
    }
}
