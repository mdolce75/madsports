package com.madsports.config;

import com.madsports.account.Product;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Locale;
import java.util.Optional;

/**
 * Dirección de cada sitio: Mad Sports (madsports.com.ar), RinkReports (rinkreports.com.ar) y RinkMetrics
 * (rinkmetrics.com.ar). La que no se configura es la de la app ({@code madsports.mail.base-url}); sin ninguna, todo
 * vive en una sola dirección, con los productos en /rinkreports y /rinkmetrics (como en local).
 */
@Component
public class SiteUrls {

    /** Los sitios: la home de la compañía y uno por producto. */
    public enum Site { HOME, REPORTS, METRICS }

    private final String home;
    private final String reports;
    private final String metrics;

    public SiteUrls(@Value("${madsports.mail.base-url}") String baseUrl,
                    @Value("${madsports.urls.home:}") String home,
                    @Value("${madsports.urls.reports:}") String reports,
                    @Value("${madsports.urls.metrics:}") String metrics) {
        String base = trim(baseUrl);
        this.home = home.isBlank() ? base : trim(home);
        this.reports = reports.isBlank() ? base : trim(reports);
        this.metrics = metrics.isBlank() ? base : trim(metrics);
    }

    /** Dirección del sitio, sin la barra final ("https://rinkreports.com.ar"). */
    public String url(Site site) {
        return switch (site) {
            case HOME -> home;
            case REPORTS -> reports;
            case METRICS -> metrics;
        };
    }

    /** Dirección del sitio del producto: la de los enlaces de sus mails. */
    public String url(Product product) {
        return url(product == Product.REPORTS ? Site.REPORTS : Site.METRICS);
    }

    /** ¿Cada sitio tiene su dominio? Si no, no hay nada que repartir entre dominios. */
    public boolean separate() {
        return !host(home).equals(host(reports)) || !host(home).equals(host(metrics));
    }

    /** El sitio de un dominio pedido ("www.rinkreports.com.ar" es RinkReports); vacío si no es ninguno. */
    public Optional<Site> site(String requestHost) {
        String h = bare(requestHost);
        for (Site s : new Site[] {Site.REPORTS, Site.METRICS, Site.HOME}) {
            if (h.equals(host(url(s)))) {
                return Optional.of(s);
            }
        }
        return Optional.empty();
    }

    private static String host(String url) {
        String h = URI.create(url).getHost();
        return bare(h == null ? "" : h);
    }

    private static String bare(String host) {
        String h = host == null ? "" : host.toLowerCase(Locale.ROOT);
        return h.startsWith("www.") ? h.substring(4) : h;
    }

    private static String trim(String url) {
        String u = url.trim();
        return u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
    }
}
