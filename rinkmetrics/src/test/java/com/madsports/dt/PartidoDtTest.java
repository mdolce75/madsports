package com.madsports.dt;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** El reloj de la planilla: cuenta hacia atrás cada tiempo de 25'. */
class PartidoDtTest {

    static final Instant T0 = Instant.parse("2026-10-16T18:00:00Z");

    static PartidoDt partido() {
        return new PartidoDt(1L, "FPP", 74, 1L, 500, 9001, "Apertura", "HURACAN",
                LocalDate.of(2026, 10, 16), true, 25, T0);
    }

    static Instant mas(int segundos) {
        return T0.plusSeconds(segundos);
    }

    @Test
    void alIniciarMarca25YDescuenta() {
        PartidoDt p = partido();
        assertThat(p.getEstado()).isEqualTo(EstadoPartido.PENDIENTE);
        assertThat(p.restante(T0)).isEqualTo(1500);
        p.iniciar(T0);
        assertThat(p.corriendo()).isTrue();
        assertThat(p.restante(mas(390))).isEqualTo(1110); // 18:30 en el reloj
        assertThat(p.jugados(mas(390))).isEqualTo(390);   // es lo que se guarda en la acción
        assertThat(p.restante(mas(5000))).isZero();        // no baja de 00:00
    }

    @Test
    void pausarYReanudar() {
        PartidoDt p = partido();
        p.iniciar(T0);
        p.pausar(mas(60));
        assertThat(p.jugados(mas(600))).isEqualTo(60);
        assertThatThrownBy(() -> p.pausar(mas(600))).isInstanceOf(IllegalStateException.class);
        p.reanudar(mas(600));
        assertThat(p.jugados(mas(630))).isEqualTo(90);
    }

    @Test
    void entretiempoYSegundoTiempo() {
        PartidoDt p = partido();
        assertThatThrownBy(() -> p.pausar(T0)).hasMessage("Primero iniciá el partido.");
        p.iniciar(T0);
        p.finTiempo(mas(1500));
        assertThat(p.getEstado()).isEqualTo(EstadoPartido.ENTRETIEMPO);
        assertThat(p.restante(mas(1600))).isZero();
        assertThatThrownBy(() -> p.reanudar(mas(1600))).hasMessage("Es el entretiempo: iniciá el 2T.");
        p.iniciar(mas(2000));
        assertThat(p.getTiempoActual()).isEqualTo(2);
        assertThat(p.restante(mas(2000))).isEqualTo(1500);
        p.finTiempo(mas(3500));
        assertThat(p.getEstado()).isEqualTo(EstadoPartido.TERMINADO);
        assertThatThrownBy(() -> p.iniciar(mas(3600))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void ajustarElReloj() {
        PartidoDt p = partido();
        p.iniciar(T0);
        p.ajustar(120, mas(10));   // se inició 2' tarde
        assertThat(p.jugados(mas(10))).isEqualTo(130);
        assertThat(p.corriendo()).isTrue();
        p.ajustar(-500, mas(10));
        assertThat(p.jugados(mas(10))).isZero();
        p.ajustar(9999, mas(10));
        assertThat(p.restante(mas(10))).isZero();
    }

    @Test
    void cerrarYReabrirDentroDe48Horas() {
        PartidoDt p = partido();
        p.iniciar(T0);
        p.cerrar(mas(100));
        assertThat(p.aceptaAcciones()).isFalse();
        assertThat(p.corriendo()).isFalse();
        p.reabrir(mas(100 + 47 * 3600));
        assertThat(p.getEstado()).isEqualTo(EstadoPartido.TERMINADO);
        assertThat(p.aceptaAcciones()).isTrue();
        p.cerrar(mas(200));
        assertThatThrownBy(() -> p.reabrir(mas(200 + 49 * 3600))).hasMessageContaining("48 horas");
    }

    @Test
    void inicioDelMesDeSuscripcion() {
        LocalDate alta = LocalDate.of(2026, 8, 15);
        assertThat(DtService.inicioDelMes(alta, LocalDate.of(2026, 10, 16))).isEqualTo(LocalDate.of(2026, 10, 15));
        assertThat(DtService.inicioDelMes(alta, LocalDate.of(2026, 10, 14))).isEqualTo(LocalDate.of(2026, 9, 15));
        assertThat(DtService.inicioDelMes(alta, alta)).isEqualTo(alta);
    }
}
