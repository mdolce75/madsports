package com.madsports.report;

import com.madsports.account.MonthlyReports;
import com.madsports.account.Profile;
import com.madsports.account.SubscriptionPlan;
import com.madsports.account.UserAccount;
import com.madsports.account.UserAccountRepository;
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
public class ReportQuota implements MonthlyReports {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d/M");

    private final RequestedReportRepository requests;
    private final UserAccountRepository users;
    private final Clock clock;
    /** Un pedido a la vez por usuario: dos pedidos simultáneos no pasan los dos el último lugar del cupo. */
    private final ConcurrentHashMap<Long, Object> locks = new ConcurrentHashMap<>();

    public ReportQuota(RequestedReportRepository requests, UserAccountRepository users, Clock clock) {
        this.requests = requests;
        this.users = users;
        this.clock = clock;
    }

    /**
     * El cupo del mes del usuario.
     *
     * @param limite  informes por mes (0 = sin límite)
     * @param renueva día en que vuelve a empezar (el 1 del mes siguiente)
     */
    public record Quota(long used, int limit, LocalDate renews) {

        public boolean exceeded() {
            return limit > 0 && used >= limit;
        }
    }

    /** El cupo rechazado: la API responde 429 con el mensaje. */
    public static class QuotaExceededException extends RuntimeException {
        public QuotaExceededException(String message) {
            super(message);
        }
    }

    @Override
    public Map<String, Object> de(UserAccount u) {
        Quota c = quota(u);
        return Map.of("used", c.used(), "limit", c.limit(), "renews", c.renews().toString());
    }

    public Quota quota(UserAccount u) {
        LocalDate today = LocalDate.now(clock);
        LocalDate from = today.withDayOfMonth(1);
        long used = requests.countByUserIdAndCreatedAtGreaterThanEqual(u.getId(),
                from.atStartOfDay(clock.getZone()).toInstant());
        return new Quota(used, limit(u), from.plusMonths(1));
    }

    /** Informes por mes del usuario: el de su plan para el jugador; el club, sin límite. */
    static int limit(UserAccount u) {
        SubscriptionPlan plan = u.getSubscriptionPlan();
        boolean player = u.getProfiles().stream().anyMatch(Profile::player);
        return player && plan != null ? plan.getReportsPerMonth() : 0;
    }

    /**
     * Pide un informe dentro del cupo: si queda lugar, hace el pedido y lo cuenta. Un pedido inválido (que tira una
     * excepción) no cuenta.
     *
     * @throws QuotaExceededException si ya usó los informes del mes
     */
    public <T> T consume(UserAccount u, Supplier<T> request) {
        synchronized (locks.computeIfAbsent(u.getId(), k -> new Object())) {
            Quota c = quota(u);
            if (c.exceeded()) {
                throw new QuotaExceededException("Ya pediste los " + c.limit() + " informes de este mes. El cupo se renueva el "
                        + c.renews().format(DAY) + ".");
            }
            T outcome = request.get();
            requests.save(new RequestedReport(u.getId(), clock.instant()));
            users.countReport(u.getUsername());
            return outcome;
        }
    }

    /** El cupo agotado, en todas las APIs: 429 con el mensaje. */
    @RestControllerAdvice
    static class QuotaErrorHandler {

        @ExceptionHandler(QuotaExceededException.class)
        ProblemDetail exceeded(QuotaExceededException e) {
            return ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
        }
    }
}
