package com.madsports.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Map;

/** Direcciones amigables para páginas estáticas. */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /** Página de antes -> página de ahora. */
    public static final Map<String, String> OLD_PAGES = Map.ofEntries(
            Map.entry("/informes.html", "/rinkreports"),
            Map.entry("/club.html", "/rinkreports/reports.html"),
            Map.entry("/jugador.html", "/rinkreports/reports.html"),
            Map.entry("/rinkreports/jugador.html", "/rinkreports/reports.html"),
            // Antes, una pantalla para el club y otra para el jugador; ahora, una sola para los dos informes.
            Map.entry("/rinkreports/club.html", "/rinkreports/reports.html"),
            Map.entry("/rinkreports/player.html", "/rinkreports/reports.html"),
            Map.entry("/rinkreports/ejemplo.html", "/rinkreports/sample.html"),
            Map.entry("/rinkreports/registro.html", "/rinkreports/signup.html"),
            Map.entry("/dt.html", "/coach.html"),
            Map.entry("/dt-demo.html", "/coach-demo.html"),
            Map.entry("/dt-inicio.html", "/rinkmetrics"),
            Map.entry("/por-que.html", "/rinkmetrics/why.html"),
            Map.entry("/login.html", "/rinkmetrics/login.html"),
            Map.entry("/registro.html", "/rinkmetrics/signup.html"),
            Map.entry("/olvido.html", "/forgot-password.html"),
            Map.entry("/restablecer.html", "/reset-password.html"));

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // La home (/) es la de Mad Sports, la compañía; la dirección vieja de su página la lleva ahí.
        registry.addRedirectViewController("/madsports", "/");
        registry.addRedirectViewController("/madsports.html", "/");
        // Portada de cada producto.
        for (String p : new String[] {"/rinkreports", "/rinkreports/"}) {
            registry.addViewController(p).setViewName("forward:/rinkreports/index.html");
        }
        for (String p : new String[] {"/rinkmetrics", "/rinkmetrics/"}) {
            registry.addViewController(p).setViewName("forward:/rinkmetrics/index.html");
        }
        // Direcciones de antes (los informes se mudaron a RinkReports y las páginas pasaron al inglés): llevan a las
        // nuevas, con los mismos parámetros (los enlaces de los mails ya mandados siguen andando).
        OLD_PAGES.forEach((old, page) -> registry.addRedirectViewController(old, page).setKeepQueryParams(true));
    }
}
