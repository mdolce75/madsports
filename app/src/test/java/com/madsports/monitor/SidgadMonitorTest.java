package com.madsports.monitor;

import com.madsports.Fixtures;
import com.madsports.account.MailContent;
import com.madsports.account.Mailer;
import com.madsports.catalog.CatalogService;
import com.madsports.config.SiteUrls;
import com.madsports.sidgad.SidgadProperties;
import com.madsports.sidgad.SidgadClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.actuate.health.Status;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class SidgadMonitorTest {

    static final SidgadProperties PROPS = new SidgadProperties("http://localhost/", "http://localhost", Duration.ZERO, 1,
                    Duration.ofMinutes(1), Duration.ofMinutes(1), Duration.ofSeconds(1), List.of(2026, 2027), 1);

    final Mailer mailer = mock(Mailer.class);

    /** SIDGAD con las páginas guardadas; {@code sheet} decide qué devuelve el acta de cada partido. */
    static class FakeSite extends SidgadClient {
        String portal = Fixtures.read("portal.html");
        String calendarOf = Fixtures.read("cal_779.html");
        IntFunction<String> sheet = idp -> Fixtures.read("gr_26389.html");

        FakeSite() {
            super(PROPS);
        }

        @Override
        public String portal() {
            return portal;
        }

        @Override
        public String catalog() {
            return Fixtures.read("catalogo.html");
        }

        @Override
        public String calendar(int idc) {
            return calendarOf;
        }

        @Override
        public String gameSheet(int idc, int idp) {
            return sheet.apply(idp);
        }
    }

    SidgadMonitor monitor(FakeSite site, String alerts) {
        return new SidgadMonitor(site, new CatalogService(site, PROPS), mailer,
                Clock.fixed(Instant.parse("2026-10-19T11:00:00Z"), ZoneOffset.UTC), alerts,
                new SiteUrls("https://rinkmetrics.test", "", "", ""));
    }

    @Test
    void withTheCurrentFormatItWorksAndDoesNotAlert() {
        SidgadMonitor m = monitor(new FakeSite(), "alertas@example.com");
        assertThat(m.health().getStatus()).isEqualTo(Status.UNKNOWN);

        SidgadMonitor.Outcome r = m.check();

        assertThat(r.ok()).isTrue();
        assertThat(r.detail()).startsWith("2026: 24 competiciones FPP;").contains("jugadores");
        assertThat(m.health().getStatus()).isEqualTo(Status.UP);
        verify(mailer, never()).send(any());
    }

    @Test
    void ifTheSheetChangesItFailsAlertsAndThenAlertsRecovery() {
        FakeSite site = new FakeSite();
        site.sheet = idp -> "<html><body><div class='nuevo-formato'>Acta</div></body></html>";
        SidgadMonitor m = monitor(site, "alertas@example.com");

        SidgadMonitor.Outcome r = m.check();

        assertThat(r.ok()).isFalse();
        assertThat(r.detail()).contains("no trae planteles");
        assertThat(m.health().getStatus()).isEqualTo(Status.DOWN);
        ArgumentCaptor<MailContent> mail = ArgumentCaptor.forClass(MailContent.class);
        verify(mailer).send(mail.capture());
        assertThat(mail.getValue().to()).isEqualTo("alertas@example.com");
        assertThat(mail.getValue().subject()).isEqualTo("RinkMetrics: Falla en la lectura de SIDGAD");

        site.sheet = idp -> Fixtures.read("gr_26389.html");
        assertThat(m.check().ok()).isTrue();
        verify(mailer, times(2)).send(mail.capture());
        assertThat(mail.getValue().subject()).isEqualTo("RinkMetrics: SIDGAD volvió a responder bien");
        // Un día bien después de otro bien no manda nada.
        m.check();
        verify(mailer, times(2)).send(any());
    }

    @Test
    void portalWithoutSeasonsOrEmptyCalendarAreFailures() {
        FakeSite site = new FakeSite();
        site.portal = "<html><body>Mantenimiento</body></html>";
        assertThat(monitor(site, "").check().detail()).startsWith("El portal no trae temporadas");

        FakeSite empty = new FakeSite();
        empty.calendarOf = "<html><body><table></table></body></html>";
        assertThat(monitor(empty, "").check().detail()).startsWith("Ningún calendario");
        // Sin ALERTAS_EMAIL solo queda en el log.
        verify(mailer, never()).send(any());
    }

    @Test
    void aNetworkErrorIsAFailure() {
        FakeSite site = new FakeSite() {
            @Override
            public String catalog() {
                throw new SidgadException("SIDGAD no responde: timeout");
            }
        };
        SidgadMonitor.Outcome r = monitor(site, "").check();
        assertThat(r.ok()).isFalse();
        assertThat(r.detail()).isEqualTo("SIDGAD no responde: timeout");
    }
}
