package com.madsports.account;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

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
}
