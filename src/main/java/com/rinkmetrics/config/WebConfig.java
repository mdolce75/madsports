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
    }
}
