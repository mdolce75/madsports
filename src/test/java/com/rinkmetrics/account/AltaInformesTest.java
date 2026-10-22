package com.rinkmetrics.account;

import com.rinkmetrics.catalog.CatalogService;
import com.rinkmetrics.catalog.CatalogService.ClubView;
import com.rinkmetrics.catalog.RosterService;
import com.rinkmetrics.catalog.RosterService.RosterPlayer;
import com.rinkmetrics.sidgad.model.Federacion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * El alta de cuentas con la configuración de la app: abierta para jugadores (Informes) y DT, cerrada para clubes. El
 * jugador (solo de la FPP, por ahora) empieza con el plan Jugador free (hasta el 31/12/2026); el mensual figura como
 * "Próximamente".
 */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:alta-informes;DB_CLOSE_DELAY=-1",
        "rinkmetrics.registro.club=false", "rinkmetrics.registro.jugador-pago=false",
        "rinkmetrics.registro.jugador-amp=false"})
class AltaInformesTest {

    @Autowired RegistroService registro;
    @Autowired UsuarioRepository usuarios;
    @Autowired SuscripcionTypeRepository planes;
    @MockitoBean CatalogService catalog;
    @MockitoBean RosterService roster;

    @BeforeEach
    void setUp() {
        usuarios.deleteAll();
        when(catalog.defaultYear()).thenReturn(2026);
        when(catalog.club(eq(Federacion.FPP), eq(74), anyInt())).thenReturn(new ClubView(74, "CAVS", "VELEZ", List.of()));
        when(roster.find(Federacion.FPP, 74, 2026, "JUVENIL", "MASCULINO", 3851))
                .thenReturn(Optional.of(new RosterPlayer(3851, "PEREZ JUAN", "Juan", "Pérez", "JUVENIL")));
    }

    static RegistroService.Registro jugador(String plan) {
        return new RegistroService.Registro(Perfil.PLAYERFPP, "juan", "clave-segura", "juan@example.com", 74,
                "JUVENIL", "MASCULINO", 3851, plan);
    }

    @Test
    void noSeCreanCuentasDeClub() {
        for (Perfil p : new Perfil[] {Perfil.CLUBFPP, Perfil.CLUBAMP}) {
            assertThatThrownBy(() -> registro.registrar(new RegistroService.Registro(p, "ana", "clave-segura",
                    "ana@example.com", 74, null, null, null, "CLUB_MENSUAL")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Las cuentas de club estarán disponibles próximamente. Por ahora podés crear tu cuenta "
                            + "de jugador.");
        }
    }

    @Test
    void elJugadorSeRegistraConElPlanFreeHastaFinDe2026() {
        Usuario u = registro.registrar(jugador("PLAYER_FREE"));

        assertThat(u.getPerfiles()).containsExactly(Perfil.PLAYERFPP);
        assertThat(u.getSuscripcionType().getCodigo()).isEqualTo("PLAYER_FREE");
        assertThat(u.getSuscripcionHasta()).isEqualTo(LocalDate.of(2026, 12, 31));
        assertThat(u.getJugadorId()).isEqualTo(3851);
    }

    @Test
    void todaviaNoSeCreanCuentasDeJugadorDeLaAmp() {
        assertThatThrownBy(() -> registro.registrar(new RegistroService.Registro(Perfil.PLAYERAMP, "juan", "clave-segura",
                "juan@example.com", 3, "JUVENIL", "MASCULINO", 6188, "PLAYER_FREE")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Las cuentas de jugador de la AMP estarán disponibles próximamente. Por ahora, las de la FPP.");
    }

    @Test
    void elPlanPagoDelJugadorTodaviaNoSePuedeElegir() {
        assertThatThrownBy(() -> registro.registrar(jugador("PLAYER_MENSUAL")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("El plan Jugador mensual estará disponible próximamente. Por ahora, empezá con el plan "
                        + "Jugador free.");
        assertThat(registro.disponible(planes.findByCodigo("PLAYER_FREE").orElseThrow())).isTrue();
        assertThat(registro.disponible(planes.findByCodigo("PLAYER_MENSUAL").orElseThrow())).isFalse();
        assertThat(registro.disponible(planes.findByCodigo("DT_MENSUAL").orElseThrow())).isFalse();
    }
}
