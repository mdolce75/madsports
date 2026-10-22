package com.rinkmetrics.report;

import com.rinkmetrics.account.InformesDelMes;
import com.rinkmetrics.account.Perfil;
import com.rinkmetrics.account.SuscripcionType;
import com.rinkmetrics.account.Usuario;
import com.rinkmetrics.account.UsuarioRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Informes pedidos y cupo del mes. Cada pedido suma uno al contador del usuario y queda registrado con su fecha. El
 * jugador tiene un cupo por mes calendario (el de su plan: 5 en el Jugador free); al llegar al cupo, el pedido se
 * rechaza (429) hasta el día 1 del mes siguiente. El club no tiene límite.
 */
@Service
public class CupoInformes implements InformesDelMes {

    private static final DateTimeFormatter DIA = DateTimeFormatter.ofPattern("d/M");

    private final InformePedidoRepository pedidos;
    private final UsuarioRepository usuarios;
    private final Clock clock;
    /** Un pedido a la vez por usuario: dos pedidos simultáneos no pasan los dos el último lugar del cupo. */
    private final ConcurrentHashMap<Long, Object> candados = new ConcurrentHashMap<>();

    public CupoInformes(InformePedidoRepository pedidos, UsuarioRepository usuarios, Clock clock) {
        this.pedidos = pedidos;
        this.usuarios = usuarios;
        this.clock = clock;
    }

    /**
     * El cupo del mes del usuario.
     *
     * @param limite  informes por mes (0 = sin límite)
     * @param renueva día en que vuelve a empezar (el 1 del mes siguiente)
     */
    public record Cupo(long usados, int limite, LocalDate renueva) {

        public boolean agotado() {
            return limite > 0 && usados >= limite;
        }
    }

    /** El cupo rechazado: la API responde 429 con el mensaje. */
    public static class CupoAgotado extends RuntimeException {
        public CupoAgotado(String message) {
            super(message);
        }
    }

    @Override
    public Map<String, Object> de(Usuario u) {
        Cupo c = cupo(u);
        return Map.of("usados", c.usados(), "limite", c.limite(), "renueva", c.renueva().toString());
    }

    public Cupo cupo(Usuario u) {
        LocalDate hoy = LocalDate.now(clock);
        LocalDate desde = hoy.withDayOfMonth(1);
        long usados = pedidos.countByUsuarioIdAndCreadoGreaterThanEqual(u.getId(),
                desde.atStartOfDay(clock.getZone()).toInstant());
        return new Cupo(usados, limite(u), desde.plusMonths(1));
    }

    /** Informes por mes del usuario: el de su plan para el jugador; el club, sin límite. */
    static int limite(Usuario u) {
        SuscripcionType plan = u.getSuscripcionType();
        boolean jugador = u.getPerfiles().stream().anyMatch(Perfil::player);
        return jugador && plan != null ? plan.getInformesPorMes() : 0;
    }

    /**
     * Pide un informe dentro del cupo: si queda lugar, hace el pedido y lo cuenta. Un pedido inválido (que tira una
     * excepción) no cuenta.
     *
     * @throws CupoAgotado si ya usó los informes del mes
     */
    public <T> T pedir(Usuario u, Supplier<T> pedido) {
        synchronized (candados.computeIfAbsent(u.getId(), k -> new Object())) {
            Cupo c = cupo(u);
            if (c.agotado()) {
                throw new CupoAgotado("Ya pediste los " + c.limite() + " informes de este mes. El cupo se renueva el "
                        + c.renueva().format(DIA) + ".");
            }
            T resultado = pedido.get();
            pedidos.save(new InformePedido(u.getId(), clock.instant()));
            usuarios.contarInforme(u.getUsuario());
            return resultado;
        }
    }

    /** El cupo agotado, en todas las APIs: 429 con el mensaje. */
    @RestControllerAdvice
    static class Respuesta {

        @ExceptionHandler(CupoAgotado.class)
        ProblemDetail agotado(CupoAgotado e) {
            return ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
        }
    }
}
