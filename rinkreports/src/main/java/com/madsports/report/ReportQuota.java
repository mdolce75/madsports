package com.madsports.report;

import com.madsports.account.MonthlyReports;
import com.madsports.account.Profile;
import com.madsports.account.SubscriptionPlan;
import com.madsports.account.UserAccount;
import com.madsports.account.UserAccountRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Informes pedidos y cupo del mes. Cada pedido suma uno al contador del usuario y queda registrado con su fecha. El
 * jugador tiene un cupo por mes calendario (el de su plan: 5 en el Jugador free); al llegar al cupo, el pedido se
 * rechaza (429) hasta el día 1 del mes siguiente. El club no tiene límite.
 * <p>
 * Lanzamiento ({@code madsports.reports.promo-*}): entre {@code promo-from} y {@code promo-until} todos, club y
 * jugador, tienen un cupo único para todo ese período ({@code promo-limit}, 5) en lugar del del mes; el día siguiente
 * al fin vuelve el cupo de siempre. Sin {@code promo-until}, no hay lanzamiento.
 */
@Service
public class ReportQuota implements MonthlyReports {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d/M");

    private final RequestedReportRepository requests;
    private final UserAccountRepository users;
    private final Clock clock;
    private final ReportArchive archive;
    /**
     * Un pedido a la vez por usuario: dos pedidos simultáneos no pasan los dos el último lugar del cupo. Un lock (no
     * synchronized): el pedido consulta la base y, en Java 21, esperar en un synchronized fija el hilo virtual a su
     * núcleo. Cada lock sale del mapa cuando nadie lo usa: no queda uno por usuario para siempre.
     */
    private final ConcurrentHashMap<Long, UserLock> locks = new ConcurrentHashMap<>();
    /** Lanzamiento: primer día (sin él, cuenta todo lo pedido), último día (null = sin lanzamiento) y su cupo. */
    private final LocalDate promoFrom;
    private final LocalDate promoUntil;
    private final int promoLimit;

    public ReportQuota(RequestedReportRepository requests, UserAccountRepository users, Clock clock,
                       ReportArchive archive, @Value("${madsports.reports.promo-from:}") String promoFrom,
                       @Value("${madsports.reports.promo-until:}") String promoUntil,
                       @Value("${madsports.reports.promo-limit:5}") int promoLimit) {
        this.promoUntil = promoUntil == null || promoUntil.isBlank() ? null : LocalDate.parse(promoUntil.trim());
        this.promoFrom = promoFrom == null || promoFrom.isBlank() ? LocalDate.EPOCH : LocalDate.parse(promoFrom.trim());
        this.promoLimit = promoLimit;
        this.requests = requests;
        this.users = users;
        this.clock = clock;
        this.archive = archive;
    }

    /**
     * El cupo del usuario: el del mes o, durante el lanzamiento, el de todo el período.
     *
     * @param limit  informes del período (0 = sin límite)
     * @param renews día en que vuelve a empezar (el 1 del mes siguiente, o el día después del lanzamiento)
     * @param until  último día del lanzamiento; null fuera de él (cupo del mes)
     */
    public record Quota(long used, int limit, LocalDate renews, LocalDate until) {

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
        Map<String, Object> out = new HashMap<>(Map.of("used", c.used(), "limit", c.limit(),
                "renews", c.renews().toString()));
        if (c.until() != null) {
            out.put("until", c.until().toString());
        }
        return out;
    }

    public Quota quota(UserAccount u) {
        LocalDate today = LocalDate.now(clock);
        boolean promo = promoUntil != null && !today.isBefore(promoFrom) && !today.isAfter(promoUntil);
        LocalDate from = promo ? promoFrom : today.withDayOfMonth(1);
        long used = requests.countByUserIdAndCreatedAtGreaterThanEqual(u.getId(),
                from.atStartOfDay(clock.getZone()).toInstant());
        return promo ? new Quota(used, promoLimit, promoUntil.plusDays(1), promoUntil)
                : new Quota(used, limit(u), today.withDayOfMonth(1).plusMonths(1), null);
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
        return consume(u, null, request);
    }

    /**
     * Lo mismo, y el pedido queda en el historial del usuario con qué informe fue.
     *
     * @param detail "Club completo · Apertura + Clausura 2026", "Jugador · Apertura 2026"
     */
    public <T> T consume(UserAccount u, String detail, Supplier<T> request) {
        UserLock lock = locks.compute(u.getId(), (id, l) -> (l == null ? new UserLock() : l).take());
        lock.lock.lock();
        try {
            Quota c = quota(u);
            if (c.exceeded()) {
                throw new QuotaExceededException(c.until() != null
                        ? "Ya pediste los " + c.limit() + " informes disponibles hasta el " + c.until().format(DAY)
                        + ". El cupo se renueva el " + c.renews().format(DAY) + "."
                        : "Ya pediste los " + c.limit() + " informes de este mes. El cupo se renueva el "
                        + c.renews().format(DAY) + ".");
            }
            T outcome = request.get();
            RequestedReport saved = requests.save(new RequestedReport(u.getId(), clock.instant(), detail));
            // Al quedar listo, sus archivos se guardan para tenerlo a mano desde el historial.
            if (outcome instanceof ReadyReport report && saved != null && saved.getId() != null) {
                report.whenReady(() -> archive.save(saved.getId(), u.getId(), report));
            }
            users.countReport(u.getId());
            return outcome;
        } finally {
            lock.lock.unlock();
            locks.compute(u.getId(), (id, l) -> l.release() ? null : l);
        }
    }

    /** Usuarios con un pedido en curso o esperando: los locks que hay en el mapa. */
    int lockedUsers() {
        return locks.size();
    }

    /** El lock de un usuario y cuántos pedidos lo usan (el que corre y los que esperan), contados en compute. */
    private static final class UserLock {
        final ReentrantLock lock = new ReentrantLock();
        int holders;

        UserLock take() {
            holders++;
            return this;
        }

        /** @return si ya nadie lo usa */
        boolean release() {
            return --holders == 0;
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
