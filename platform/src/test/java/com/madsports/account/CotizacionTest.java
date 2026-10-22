package com.madsports.account;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CotizacionTest {

    static final Clock HOY = Clock.fixed(Instant.parse("2026-10-15T15:00:00Z"), ZoneOffset.UTC);

    @Test
    void leeElDolarOficialDeDolarApi() throws Exception {
        Cotizacion.Valor v = Cotizacion.leer("""
                {"moneda":"USD","casa":"oficial","nombre":"Oficial","compra":1480,"venta":1530,
                 "fechaActualizacion":"2026-10-15T18:00:00.000Z"}""", HOY);

        assertThat(v.pesosPorDolar()).isEqualByComparingTo("1530");
        assertThat(v.casa()).isEqualTo("Oficial");
        assertThat(v.fecha()).isEqualTo(LocalDate.of(2026, 10, 15));
        assertThat(v.respaldo()).isFalse();
        assertThatThrownBy(() -> Cotizacion.leer("{\"venta\":0}", HOY)).isInstanceOf(java.io.IOException.class);
    }

    @Test
    void preciosEnPesosRedondeadosHaciaAbajoACentenas() {
        Cotizacion.Valor v = new Cotizacion.Valor(new BigDecimal("1530"), "Oficial", LocalDate.of(2026, 10, 15), false);

        assertThat(Cotizacion.enPesos(new BigDecimal("15"), v)).isEqualByComparingTo("22900");   // 22.950
        assertThat(Cotizacion.enPesos(new BigDecimal("280"), v)).isEqualByComparingTo("428400");
        assertThat(Cotizacion.enPesos(new BigDecimal("26.67"), v)).isEqualByComparingTo("40800"); // 40.805,10
        assertThat(Cotizacion.enPesos(new BigDecimal("12.50"), v)).isEqualByComparingTo("19100"); // 19.125
    }

    @Test
    void sinDolarApiUsaElRespaldo() {
        Cotizacion c = new Cotizacion("", new BigDecimal("1500"), HOY);

        assertThat(c.actual().pesosPorDolar()).isEqualByComparingTo("1500");
        assertThat(c.actual().respaldo()).isTrue();
        assertThat(c.enPesos(new BigDecimal("30"))).isEqualByComparingTo("45000");
    }
}
