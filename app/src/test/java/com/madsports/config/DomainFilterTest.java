package com.madsports.config;

import com.madsports.account.Product;
import com.madsports.config.SiteUrls.Site;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/** Un dominio por sitio: madsports, rinkreports y rinkmetrics (DomainFilter, SiteUrls). */
class DomainFilterTest {

    static final SiteUrls URLS = new SiteUrls("https://madsports.com.ar", "https://madsports.com.ar/",
            "https://rinkreports.com.ar", "https://rinkmetrics.com.ar");

    record Result(MockHttpServletResponse response, MockFilterChain chain) {

        boolean passed() {
            return chain.getRequest() != null;
        }
    }

    static Result get(String host, String path, String query) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", path);
        req.setServerName(host);
        req.setQueryString(query);
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        new DomainFilter(URLS).doFilter(req, res, chain);
        return new Result(res, chain);
    }

    @Test
    void theRootOfEachProductIsItsHome() throws Exception {
        assertThat(get("rinkreports.com.ar", "/", null).response().getForwardedUrl()).isEqualTo("/rinkreports/index.html");
        assertThat(get("www.rinkmetrics.com.ar", "/", null).response().getForwardedUrl())
                .isEqualTo("/rinkmetrics/index.html");
        // La de madsports es la home de la compañía.
        assertThat(get("madsports.com.ar", "/", null).passed()).isTrue();
    }

    @Test
    void aProductPageOnAnotherDomainGoesToItsOwn() throws Exception {
        assertThat(get("rinkreports.com.ar", "/rinkmetrics/login.html", "returnTo=%2Fcoach.html").response()
                .getRedirectedUrl()).isEqualTo("https://rinkmetrics.com.ar/rinkmetrics/login.html?returnTo=%2Fcoach.html");
        assertThat(get("madsports.com.ar", "/coach.html", null).response().getRedirectedUrl())
                .isEqualTo("https://rinkmetrics.com.ar/coach.html");
        // La portada del producto, a la raíz de su dominio.
        assertThat(get("madsports.com.ar", "/rinkreports", null).response().getRedirectedUrl())
                .isEqualTo("https://rinkreports.com.ar/");
        assertThat(get("rinkreports.com.ar", "/rinkreports/", "x=1").response().getRedirectedUrl()).isEqualTo("/?x=1");
        // "Mad Sports" desde un producto: la home.
        assertThat(get("rinkmetrics.com.ar", "/madsports", null).response().getRedirectedUrl())
                .isEqualTo("https://madsports.com.ar/");
    }

    @Test
    void ownPagesCommonPagesAndTheApiPassOnAnyDomain() throws Exception {
        assertThat(get("rinkreports.com.ar", "/rinkreports/login.html", null).passed()).isTrue();
        assertThat(get("rinkmetrics.com.ar", "/coach.html", null).passed()).isTrue();
        for (String path : new String[] {"/api/session", "/css/app.css", "/js/app.js", "/forgot-password.html"}) {
            assertThat(get("rinkreports.com.ar", path, null).passed()).as(path).isTrue();
        }
        // Otro dominio (localhost) o un POST: no se tocan.
        assertThat(get("localhost", "/rinkreports", null).passed()).isTrue();
        MockHttpServletRequest post = new MockHttpServletRequest("POST", "/rinkmetrics/login.html");
        post.setServerName("rinkreports.com.ar");
        MockFilterChain chain = new MockFilterChain();
        new DomainFilter(URLS).doFilter(post, new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void theUrlsOfEachSite() {
        assertThat(URLS.url(Site.HOME)).isEqualTo("https://madsports.com.ar");
        assertThat(URLS.url(Product.REPORTS)).isEqualTo("https://rinkreports.com.ar");
        assertThat(URLS.url(Product.METRICS)).isEqualTo("https://rinkmetrics.com.ar");
        assertThat(URLS.site("WWW.RinkReports.com.ar")).contains(Site.REPORTS);
        assertThat(URLS.site("otro.com.ar")).isEmpty();
        assertThat(URLS.separate()).isTrue();
        // Sin dominios configurados, todo en la dirección de la app: el filtro no se activa.
        SiteUrls single = new SiteUrls("http://localhost:8080/", "", "", "");
        assertThat(single.separate()).isFalse();
        assertThat(single.url(Product.REPORTS)).isEqualTo("http://localhost:8080");
    }
}
