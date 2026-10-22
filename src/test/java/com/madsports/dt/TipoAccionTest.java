package com.madsports.dt;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TipoAccionTest {

    @Test
    void resultadosQueAdmiteCadaAccion() {
        assertThat(TipoAccion.PERDIDA.admite(Resultado.RIVAL)).isTrue();
        assertThat(TipoAccion.PERDIDA.admite(Resultado.GOL)).isFalse();
        assertThat(TipoAccion.PERDIDA.admite(null)).isFalse();
        assertThat(TipoAccion.ATAJADA.admite(null)).isTrue();
        assertThat(TipoAccion.ATAJADA.admite(Resultado.GOL)).isFalse();
        assertThat(TipoAccion.TARJETA.admite(Resultado.AZUL)).isTrue();
        assertThat(TipoAccion.PENAL_CONTRA.admite(Resultado.ATAJADO)).isTrue();
    }

    @Test
    void golesDelMarcador() {
        assertThat(TipoAccion.TIRO.golAFavor(Resultado.GOL)).isTrue();
        assertThat(TipoAccion.LIBRE_FAVOR.golAFavor(Resultado.GOL)).isTrue();
        assertThat(TipoAccion.PENAL_FAVOR.golAFavor(Resultado.NO_GOL)).isFalse();
        assertThat(TipoAccion.GOL_RECIBIDO.golEnContra(null)).isTrue();
        assertThat(TipoAccion.PENAL_CONTRA.golEnContra(Resultado.GOL)).isTrue();
        assertThat(TipoAccion.LIBRE_CONTRA.golEnContra(Resultado.ATAJADO)).isFalse();
        assertThat(TipoAccion.ATAJADA.arquero()).isTrue();
        assertThat(TipoAccion.TIRO.arquero()).isFalse();
    }
}
