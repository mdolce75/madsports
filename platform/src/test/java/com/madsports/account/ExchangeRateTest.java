package com.madsports.account;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExchangeRateTest {

    static final Clock TODAY = Clock.fixed(Instant.parse("2026-10-15T15:00:00Z"), ZoneOffset.UTC);

    @Test
    void readsTheOfficialDollarFromDolarApi() throws Exception {
        ExchangeRate.Quote v = ExchangeRate.read("""
                {"moneda":"USD","casa":"oficial","nombre":"Oficial","compra":1480,"venta":1530,
                 "fechaActualizacion":"2026-10-15T18:00:00.000Z"}""", TODAY);

        assertThat(v.pesosPerDollar()).isEqualByComparingTo("1530");
        assertThat(v.house()).isEqualTo("Oficial");
        assertThat(v.date()).isEqualTo(LocalDate.of(2026, 10, 15));
        assertThat(v.fallback()).isFalse();
        assertThatThrownBy(() -> ExchangeRate.read("{\"venta\":0}", TODAY)).isInstanceOf(java.io.IOException.class);
    }

    @Test
    void pricesInPesosRoundedDownToHundreds() {
        ExchangeRate.Quote v = new ExchangeRate.Quote(new BigDecimal("1530"), "Oficial", LocalDate.of(2026, 10, 15), false);

        assertThat(ExchangeRate.inPesos(new BigDecimal("15"), v)).isEqualByComparingTo("22900");   // 22.950
        assertThat(ExchangeRate.inPesos(new BigDecimal("280"), v)).isEqualByComparingTo("428400");
        assertThat(ExchangeRate.inPesos(new BigDecimal("26.67"), v)).isEqualByComparingTo("40800"); // 40.805,10
        assertThat(ExchangeRate.inPesos(new BigDecimal("12.50"), v)).isEqualByComparingTo("19100"); // 19.125
    }

    @Test
    void withoutDolarApiItUsesTheFallback() {
        ExchangeRate c = new ExchangeRate("", new BigDecimal("1500"), TODAY);

        assertThat(c.current().pesosPerDollar()).isEqualByComparingTo("1500");
        assertThat(c.current().fallback()).isTrue();
        assertThat(c.inPesos(new BigDecimal("30"))).isEqualByComparingTo("45000");
    }

    /**
     * Mientras se consulta DolarAPI (que tarda), los demás no esperan: siguen con la cotización anterior. Antes la
     * consulta corría dentro de un synchronized y trababa a todos los que miraban los planes.
     */
    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void whileDolarApiIsSlowTheLastQuoteIsUsed() throws Exception {
        MovableClock clock = new MovableClock(TODAY.instant());
        CountDownLatch asking = new CountDownLatch(1);
        CountDownLatch answer = new CountDownLatch(1);
        ExchangeRate rate = new ExchangeRate("https://dolarapi.test", new BigDecimal("1500"), clock) {
            int calls;

            @Override
            Quote fetch() {
                if (++calls == 1) {
                    return new Quote(new BigDecimal("1530"), "Oficial", LocalDate.of(2026, 10, 15), false);
                }
                asking.countDown();
                try {
                    answer.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return new Quote(new BigDecimal("1550"), "Oficial", LocalDate.of(2026, 10, 15), false);
            }
        };
        assertThat(rate.current().pesosPerDollar()).isEqualByComparingTo("1530");

        clock.now = clock.now.plus(Duration.ofHours(2));
        CompletableFuture<ExchangeRate.Quote> slow = CompletableFuture.supplyAsync(rate::current,
                r -> Thread.ofVirtual().start(r));
        assertThat(asking.await(20, TimeUnit.SECONDS)).isTrue();
        assertThat(rate.current().pesosPerDollar()).isEqualByComparingTo("1530");

        answer.countDown();
        assertThat(slow.get().pesosPerDollar()).isEqualByComparingTo("1550");
        assertThat(rate.current().pesosPerDollar()).isEqualByComparingTo("1550");
    }

    static final class MovableClock extends Clock {
        volatile Instant now;

        MovableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(now, zone);
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
