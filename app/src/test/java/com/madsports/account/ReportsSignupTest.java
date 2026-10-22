package com.madsports.account;

import com.madsports.catalog.CatalogService;
import com.madsports.catalog.CatalogService.ClubView;
import com.madsports.catalog.RosterService;
import com.madsports.catalog.RosterService.RosterPlayer;
import com.madsports.sidgad.model.Federation;
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
        "madsports.signup.club=false", "madsports.signup.player-paid=false",
        "madsports.signup.player-amp=false"})
class ReportsSignupTest {

    @Autowired SignupService signup;
    @Autowired UserAccountRepository users;
    @Autowired SubscriptionPlanRepository plans;
    @MockitoBean CatalogService catalog;
    @MockitoBean RosterService roster;

    @BeforeEach
    void setUp() {
        users.deleteAll();
        when(catalog.defaultYear()).thenReturn(2026);
        when(catalog.club(eq(Federation.FPP), eq(74), anyInt())).thenReturn(new ClubView(74, "CAVS", "VELEZ", List.of()));
        when(roster.find(Federation.FPP, 74, 2026, "JUVENIL", "MASCULINO", 3851))
                .thenReturn(Optional.of(new RosterPlayer(3851, "PEREZ JUAN", "Juan", "Pérez", "JUVENIL")));
    }

    static SignupService.Signup player(String plan) {
        return new SignupService.Signup(Profile.PLAYERFPP, "juan", "clave-segura", "juan@example.com", 74,
                "JUVENIL", "MASCULINO", 3851, plan);
    }

    @Test
    void clubAccountsAreNotCreated() {
        for (Profile p : new Profile[] {Profile.CLUBFPP, Profile.CLUBAMP}) {
            assertThatThrownBy(() -> signup.register(new SignupService.Signup(p, "ana", "clave-segura",
                    "ana@example.com", 74, null, null, null, "CLUB_MONTHLY")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Las cuentas de club estarán disponibles próximamente. Por ahora podés crear tu cuenta "
                            + "de jugador.");
        }
    }

    @Test
    void playerSignsUpWithTheFreePlanUntilEndOf2026() {
        UserAccount u = signup.register(player("PLAYER_FREE"));

        assertThat(u.getProfiles()).containsExactly(Profile.PLAYERFPP);
        assertThat(u.getSubscriptionPlan().getCode()).isEqualTo("PLAYER_FREE");
        assertThat(u.getSubscriptionTo()).isEqualTo(LocalDate.of(2026, 12, 31));
        assertThat(u.getPlayerId()).isEqualTo(3851);
    }

    @Test
    void ampPlayerAccountsAreNotCreatedYet() {
        assertThatThrownBy(() -> signup.register(new SignupService.Signup(Profile.PLAYERAMP, "juan", "clave-segura",
                "juan@example.com", 3, "JUVENIL", "MASCULINO", 6188, "PLAYER_FREE")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Las cuentas de jugador de la AMP estarán disponibles próximamente. Por ahora, las de la FPP.");
    }

    @Test
    void thePlayerPaidPlanCannotBeChosenYet() {
        assertThatThrownBy(() -> signup.register(player("PLAYER_MONTHLY")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("El plan Jugador mensual estará disponible próximamente. Por ahora, empezá con el plan "
                        + "Jugador free.");
        assertThat(signup.available(plans.findByCode("PLAYER_FREE").orElseThrow())).isTrue();
        assertThat(signup.available(plans.findByCode("PLAYER_MONTHLY").orElseThrow())).isFalse();
        assertThat(signup.available(plans.findByCode("COACH_MONTHLY").orElseThrow())).isFalse();
    }
}
