package com.rinkmetrics.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Direcciones amigables para páginas estáticas. */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // Home de Mad Sports, la empresa dueña de RinkMetrics.
        registry.addViewController("/madsports").setViewName("forward:/madsports.html");
        // Métricas DT en React (frontend/, construida en static/dt-app): /dt-app/ abre su página.
        registry.addViewController("/dt-app").setViewName("redirect:/dt-app/");
        registry.addViewController("/dt-app/").setViewName("forward:/dt-app/index.html");
    }
}
