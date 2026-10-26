package com.madsports.config;

import com.madsports.config.SiteUrls.Site;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;
import java.util.Set;

/**
 * Cada sitio en su dominio (cuando están configurados, {@link SiteUrls}): la raíz de rinkreports.com.ar es la portada
 * de RinkReports y la de rinkmetrics.com.ar, la de RinkMetrics. Las páginas de un producto pedidas en otro dominio
 * llevan al suyo, y /madsports (el enlace "Mad Sports" de los productos), a la home de la compañía. La API, los
 * estilos, los scripts y las páginas comunes (recuperar la clave) andan en cualquiera.
 */
public class DomainFilter extends OncePerRequestFilter {

    private static final Set<String> ROOT = Set.of("/", "/index.html");
    private static final Set<String> TO_HOME = Set.of("/madsports", "/madsports.html");

    private final SiteUrls urls;

    public DomainFilter(SiteUrls urls) {
        this.urls = urls;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        Optional<Site> host = "GET".equals(req.getMethod()) || "HEAD".equals(req.getMethod())
                ? urls.site(req.getServerName()) : Optional.empty();
        if (host.isEmpty()) {
            chain.doFilter(req, res);
            return;
        }
        String path = req.getRequestURI().substring(req.getContextPath().length());
        String query = req.getQueryString() == null ? "" : "?" + req.getQueryString();
        Site site = host.get();
        if (ROOT.contains(path) && site != Site.HOME) {
            req.getRequestDispatcher(productHome(site) + "/index.html").forward(req, res);
            return;
        }
        if (TO_HOME.contains(path)) {
            res.sendRedirect(urls.url(Site.HOME) + "/");
            return;
        }
        Site page = siteOf(path);
        if (page == null) {
            chain.doFilter(req, res);
            return;
        }
        // La portada del producto es la raíz de su dominio.
        String target = isProductHome(path) ? "/" : path;
        if (page != site) {
            res.sendRedirect(urls.url(page) + target + query);
        } else if (!target.equals(path)) {
            res.sendRedirect(target + query);
        } else {
            chain.doFilter(req, res);
        }
    }

    /** El sitio de una página de producto; null para las comunes (API, estilos, scripts, recuperar la clave...). */
    private static Site siteOf(String path) {
        if (path.equals("/rinkreports") || path.startsWith("/rinkreports/")) {
            return Site.REPORTS;
        }
        if (path.equals("/rinkmetrics") || path.startsWith("/rinkmetrics/") || path.equals("/coach.html")
                || path.equals("/coach-demo.html")) {
            return Site.METRICS;
        }
        return null;
    }

    private static boolean isProductHome(String path) {
        return Set.of("/rinkreports", "/rinkreports/", "/rinkreports/index.html", "/rinkmetrics", "/rinkmetrics/",
                "/rinkmetrics/index.html").contains(path);
    }

    private static String productHome(Site site) {
        return site == Site.REPORTS ? "/rinkreports" : "/rinkmetrics";
    }

    /** Antes que la seguridad: un pedido en el dominio equivocado se manda al suyo sin pasar por el ingreso. */
    @Configuration
    static class Registration {
        @Bean
        FilterRegistrationBean<DomainFilter> domainFilter(SiteUrls urls) {
            FilterRegistrationBean<DomainFilter> bean = new FilterRegistrationBean<>(new DomainFilter(urls));
            bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
            bean.setEnabled(urls.separate());
            return bean;
        }
    }
}
