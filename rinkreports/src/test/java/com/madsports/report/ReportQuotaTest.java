package com.madsports.report;

import com.madsports.account.Profile;
import com.madsports.account.UserAccount;
import com.madsports.account.UserAccountRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Lanzamiento: del 27/10 al 31/12, club y jugador tienen 5 informes para todo el período; desde el 1/1, el de siempre. */
class ReportQuotaTest {

    static final ZoneId BUENOS_AIRES = ZoneId.of("America/Argentina/Buenos_Aires");
    static final Instant PROMO_START = LocalDate.of(2026, 10, 27).atStartOfDay(BUENOS_AIRES).toInstant();

    final RequestedReportRepository requests = mock(RequestedReportRepository.class);

    ReportQuota quotaOn(LocalDate day) {
        Clock clock = Clock.fixed(day.atTime(12, 0).atZone(BUENOS_AIRES).toInstant(), BUENOS_AIRES);
        return new ReportQuota(requests, mock(UserAccountRepository.class), clock, mock(ReportArchive.class),
                "2026-10-27", "2026-12-31", 5);
    }

    static UserAccount user(Profile profile) {
        return user(1L, profile);
    }

    static UserAccount user(long id, Profile profile) {
        UserAccount u = mock(UserAccount.class);
        when(u.getId()).thenReturn(id);
        when(u.getProfiles()).thenReturn(Set.of(profile));
        return u;
    }

    @Test
    void duringTheLaunchTheClubHas5ReportsUntilDecember31() {
        when(requests.countByUserIdAndCreatedAtGreaterThanEqual(any(), eq(PROMO_START))).thenReturn(4L);
        ReportQuota.Quota q = quotaOn(LocalDate.of(2026, 11, 15)).quota(user(Profile.CLUBFPP));
        assertThat(q.limit()).isEqualTo(5);
        assertThat(q.used()).isEqualTo(4);
        assertThat(q.until()).isEqualTo(LocalDate.of(2026, 12, 31));
        assertThat(q.renews()).isEqualTo(LocalDate.of(2027, 1, 1));
        assertThat(q.exceeded()).isFalse();
    }

    @Test
    void theSixthOfThePeriodIsRejectedForClubAndPlayer() {
        // Cuenta todo lo pedido desde el 27/10, no solo lo del mes.
        when(requests.countByUserIdAndCreatedAtGreaterThanEqual(any(), eq(PROMO_START))).thenReturn(5L);
        for (Profile p : new Profile[]{Profile.CLUBFPP, Profile.PLAYERFPP}) {
            @SuppressWarnings("unchecked")
            Supplier<String> request = mock(Supplier.class);
            assertThatThrownBy(() -> quotaOn(LocalDate.of(2026, 12, 31)).consume(user(p), request))
                    .isInstanceOf(ReportQuota.QuotaExceededException.class)
                    .hasMessage("Ya pediste los 5 informes disponibles hasta el 31/12. El cupo se renueva el 1/1.");
            verify(request, never()).get();
        }
        assertThat(quotaOn(LocalDate.of(2026, 12, 31)).de(user(Profile.CLUBFPP)))
                .containsEntry("used", 5L).containsEntry("limit", 5).containsEntry("until", "2026-12-31");
    }

    @Test
    void fromJanuary1TheUsualQuotaIsBack() {
        ReportQuota.Quota q = quotaOn(LocalDate.of(2027, 1, 2)).quota(user(Profile.CLUBFPP));
        assertThat(q.limit()).isZero();
        assertThat(q.until()).isNull();
        assertThat(q.renews()).isEqualTo(LocalDate.of(2027, 2, 1));
        assertThat(quotaOn(LocalDate.of(2027, 1, 2)).de(user(Profile.CLUBFPP))).doesNotContainKey("until");
    }

    /**
     * Pedidos de 8 usuarios a la vez, en hilos virtuales con 2 núcleos (ver surefire en el pom), cada uno esperando
     * a los demás dentro del pedido: entran todos. Con synchronized, los 2 primeros quedaban fijos a los núcleos
     * esperando y el resto no tenía dónde correr. Al terminar no queda ningún lock guardado.
     */
    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void requestsOfManyUsersAtOnceDoNotHangWithFewCores() throws Exception {
        ReportQuota quota = quotaOn(LocalDate.of(2027, 1, 2));
        int n = 8;
        CountDownLatch inside = new CountDownLatch(n);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<String>> done = new ArrayList<>();
            for (long id = 1; id <= n; id++) {
                UserAccount u = user(id, Profile.CLUBFPP);
                done.add(pool.submit(() -> quota.consume(u, () -> {
                    inside.countDown();
                    await(inside);
                    return "ok";
                })));
            }
            for (Future<String> f : done) {
                assertThat(f.get()).isEqualTo("ok");
            }
        }
        assertThat(quota.lockedUsers()).isZero();
    }

    /** Dos pedidos del mismo usuario a la vez van de a uno: el segundo espera que termine el primero. */
    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void requestsOfTheSameUserGoOneAtATime() throws Exception {
        ReportQuota quota = quotaOn(LocalDate.of(2027, 1, 2));
        AtomicInteger running = new AtomicInteger();
        AtomicInteger most = new AtomicInteger();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<String>> done = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                done.add(pool.submit(() -> quota.consume(user(7L, Profile.CLUBFPP), () -> {
                    most.accumulateAndGet(running.incrementAndGet(), Math::max);
                    sleep(20);
                    running.decrementAndGet();
                    return "ok";
                })));
            }
            for (Future<String> f : done) {
                f.get();
            }
        }
        assertThat(most.get()).isEqualTo(1);
        assertThat(quota.lockedUsers()).isZero();
    }

    static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(20, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }
}
