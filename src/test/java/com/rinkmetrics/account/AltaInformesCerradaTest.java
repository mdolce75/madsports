package com.rinkmetrics.account;

import com.rinkmetrics.catalog.CatalogService;
import com.rinkmetrics.player.RosterService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Mientras Informes no esté disponible, el registro solo acepta cuentas de Métricas DT. */
@SpringBootTest(properties = {"spring.datasource.url=${rinkmetrics.test.db:jdbc:h2:mem:alta-informes;DB_CLOSE_DELAY=-1}",
        "rinkmetrics.registro.informes=false"})
class AltaInformesCerradaTest {

    @Autowired RegistroService registro;
    @MockitoBean CatalogService catalog;
    @MockitoBean RosterService roster;

    @Test
    void noSeCreanCuentasDeClubNiDeJugador() {
        for (Perfil p : new Perfil[] {Perfil.CLUBFPP, Perfil.CLUBAMP, Perfil.PLAYERFPP, Perfil.PLAYERAMP}) {
            assertThatThrownBy(() -> registro.registrar(new RegistroService.Registro(p, "ana", "clave-segura",
                    "ana@example.com", 74, null, null, null, "CLUB_MENSUAL")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageStartingWith("Las cuentas de Informes (Club y Jugador) estarán disponibles próximamente.");
        }
    }
}
