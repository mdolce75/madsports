package com.madsports.monitor;

import com.madsports.Fixtures;
import com.madsports.account.MailContent;
import com.madsports.account.Mailer;
import com.madsports.catalog.CatalogService;
import com.madsports.config.RinkMetricsProperties;
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

    static final RinkMetricsProperties PROPS = new RinkMetricsProperties(
            new RinkMetricsProperties.Sidgad("http://localhost/", "http://localhost", Duration.ZERO, 1,
                    Duration.ofMinutes(1), Duration.ofMinutes(1), Duration.ofSeconds(1), List.of(2026, 2027)),
            new RinkMetricsProperties.Ai(false, null, "m", "http://localhost", Duration.ofSeconds(1)));

    final Mailer mailer = mock(Mailer.class);

    /** SIDGAD con las páginas guardadas; {@code acta} decide qué devuelve el acta de cada partido. */
    static class Sitio extends SidgadClient {
        String portal = Fixtures.read("portal.html");
        String calendario = Fixtures.read("cal_779.html");
        IntFunction<String> acta = idp -> Fixtures.read("gr_26389.html");

        Sitio() {
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
            return calendario;
        }

        @Override
        public String gameSheet(int idc, int idp) {
            return acta.apply(idp);
        }
    }

    SidgadMonitor monitor(Sitio sitio, String alertas) {
        return new SidgadMonitor(sitio, new CatalogService(sitio, PROPS), mailer,
                Clock.fixed(Instant.parse("2026-10-19T11:00:00Z"), ZoneOffset.UTC), alertas, "https://rinkmetrics.test");
    }

    @Test
    void conElFormatoActualAndaYNoAvisa() {
        SidgadMonitor m = monitor(new Sitio(), "alertas@example.com");
        assertThat(m.health().getStatus()).isEqualTo(Status.UNKNOWN);

        SidgadMonitor.Resultado r = m.chequear();

        assertThat(r.ok()).isTrue();
        assertThat(r.detalle()).startsWith("2026: 24 competiciones FPP;").contains("jugadores");
        assertThat(m.health().getStatus()).isEqualTo(Status.UP);
        verify(mailer, never()).send(any());
    }

    @Test
    void siCambiaElActaFallaAvisaYDespuesAvisaLaRecuperacion() {
        Sitio sitio = new Sitio();
        sitio.acta = idp -> "<html><body><div class='nuevo-formato'>Acta</div></body></html>";
        SidgadMonitor m = monitor(sitio, "alertas@example.com");

        SidgadMonitor.Resultado r = m.chequear();

        assertThat(r.ok()).isFalse();
        assertThat(r.detalle()).contains("no trae planteles");
        assertThat(m.health().getStatus()).isEqualTo(Status.DOWN);
        ArgumentCaptor<MailContent> mail = ArgumentCaptor.forClass(MailContent.class);
        verify(mailer).send(mail.capture());
        assertThat(mail.getValue().to()).isEqualTo("alertas@example.com");
        assertThat(mail.getValue().subject()).isEqualTo("RinkMetrics: Falla en la lectura de SIDGAD");

        sitio.acta = idp -> Fixtures.read("gr_26389.html");
        assertThat(m.chequear().ok()).isTrue();
        verify(mailer, times(2)).send(mail.capture());
        assertThat(mail.getValue().subject()).isEqualTo("RinkMetrics: SIDGAD volvió a responder bien");
        // Un día bien después de otro bien no manda nada.
        m.chequear();
        verify(mailer, times(2)).send(any());
    }

    @Test
    void portalSinTemporadasOCalendarioVacioSonFallas() {
        Sitio sitio = new Sitio();
        sitio.portal = "<html><body>Mantenimiento</body></html>";
        assertThat(monitor(sitio, "").chequear().detalle()).startsWith("El portal no trae temporadas");

        Sitio vacio = new Sitio();
        vacio.calendario = "<html><body><table></table></body></html>";
        assertThat(monitor(vacio, "").chequear().detalle()).startsWith("Ningún calendario");
        // Sin ALERTAS_EMAIL solo queda en el log.
        verify(mailer, never()).send(any());
    }

    @Test
    void unErrorDeRedEsFalla() {
        Sitio sitio = new Sitio() {
            @Override
            public String catalog() {
                throw new SidgadException("SIDGAD no responde: timeout");
            }
        };
        SidgadMonitor.Resultado r = monitor(sitio, "").chequear();
        assertThat(r.ok()).isFalse();
        assertThat(r.detalle()).isEqualTo("SIDGAD no responde: timeout");
    }
}
