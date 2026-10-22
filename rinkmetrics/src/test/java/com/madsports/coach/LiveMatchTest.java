package com.madsports.coach;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** El reloj de la planilla: cuenta hacia atrás cada tiempo de 25'. */
class LiveMatchTest {

    static final Instant T0 = Instant.parse("2026-10-16T18:00:00Z");

    static LiveMatch match() {
        return new LiveMatch(1L, "FPP", 74, 1L, 500, 9001, "Apertura", "HURACAN",
                LocalDate.of(2026, 10, 16), true, 25, T0);
    }

    static Instant plus(int seconds) {
        return T0.plusSeconds(seconds);
    }

    @Test
    void onStartShows25AndCountsDown() {
        LiveMatch p = match();
        assertThat(p.getStatus()).isEqualTo(MatchStatus.PENDING);
        assertThat(p.remaining(T0)).isEqualTo(1500);
        p.start(T0);
        assertThat(p.running()).isTrue();
        assertThat(p.remaining(plus(390))).isEqualTo(1110); // 18:30 en el reloj
        assertThat(p.elapsed(plus(390))).isEqualTo(390);   // es lo que se guarda en la acción
        assertThat(p.remaining(plus(5000))).isZero();        // no baja de 00:00
    }

    @Test
    void pauseAndResume() {
        LiveMatch p = match();
        p.start(T0);
        p.pause(plus(60));
        assertThat(p.elapsed(plus(600))).isEqualTo(60);
        assertThatThrownBy(() -> p.pause(plus(600))).isInstanceOf(IllegalStateException.class);
        p.resume(plus(600));
        assertThat(p.elapsed(plus(630))).isEqualTo(90);
    }

    @Test
    void halfTimeAndSecondPeriod() {
        LiveMatch p = match();
        assertThatThrownBy(() -> p.pause(T0)).hasMessage("Primero iniciá el partido.");
        p.start(T0);
        p.endPeriod(plus(1500));
        assertThat(p.getStatus()).isEqualTo(MatchStatus.HALF_TIME);
        assertThat(p.remaining(plus(1600))).isZero();
        assertThatThrownBy(() -> p.resume(plus(1600))).hasMessage("Es el entretiempo: iniciá el 2T.");
        p.start(plus(2000));
        assertThat(p.getCurrentPeriod()).isEqualTo(2);
        assertThat(p.remaining(plus(2000))).isEqualTo(1500);
        p.endPeriod(plus(3500));
        assertThat(p.getStatus()).isEqualTo(MatchStatus.FINISHED);
        assertThatThrownBy(() -> p.start(plus(3600))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void adjustTheClock() {
        LiveMatch p = match();
        p.start(T0);
        p.adjust(120, plus(10));   // se inició 2' tarde
        assertThat(p.elapsed(plus(10))).isEqualTo(130);
        assertThat(p.running()).isTrue();
        p.adjust(-500, plus(10));
        assertThat(p.elapsed(plus(10))).isZero();
        p.adjust(9999, plus(10));
        assertThat(p.remaining(plus(10))).isZero();
    }

    @Test
    void closeAndReopenWithin48Hours() {
        LiveMatch p = match();
        p.start(T0);
        p.close(plus(100));
        assertThat(p.acceptsActions()).isFalse();
        assertThat(p.running()).isFalse();
        p.reopen(plus(100 + 47 * 3600));
        assertThat(p.getStatus()).isEqualTo(MatchStatus.FINISHED);
        assertThat(p.acceptsActions()).isTrue();
        p.close(plus(200));
        assertThatThrownBy(() -> p.reopen(plus(200 + 49 * 3600))).hasMessageContaining("48 horas");
    }

    @Test
    void subscriptionMonthStart() {
        LocalDate addPlayer = LocalDate.of(2026, 8, 15);
        assertThat(CoachService.monthStart(addPlayer, LocalDate.of(2026, 10, 16))).isEqualTo(LocalDate.of(2026, 10, 15));
        assertThat(CoachService.monthStart(addPlayer, LocalDate.of(2026, 10, 14))).isEqualTo(LocalDate.of(2026, 9, 15));
        assertThat(CoachService.monthStart(addPlayer, addPlayer)).isEqualTo(addPlayer);
    }
}
