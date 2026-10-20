package com.rinkmetrics.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Direcciones amigables para páginas estáticas. */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // La home (/) es la de Mad Sports, la compañía; la dirección vieja de su página la lleva ahí.
        registry.addRedirectViewController("/madsports", "/");
        registry.addRedirectViewController("/madsports.html", "/");
        // Portada de cada producto.
        for (String p : new String[] {"/rinkreports", "/rinkreports/"}) {
            registry.addViewController(p).setViewName("forward:/informes.html");
        }
        for (String p : new String[] {"/rinkmetrics", "/rinkmetrics/"}) {
            registry.addViewController(p).setViewName("forward:/dt-inicio.html");
        }
    }
}
