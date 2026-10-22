package com.madsports.coach;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * La demo de RinkMetrics (/dt-demo.html, pública) es la misma página que /dt.html (solo para DT): no hay una copia
 * que mantener. En la demo, la página carga js/dt/demo.js, que contesta la API en el navegador con datos de ejemplo.
 * Se devuelve el archivo (no un forward), porque un forward a /dt.html volvería a pedir el perfil de DT.
 */
@Controller
class CoachDemoController {

    private static final Resource PAGE = new ClassPathResource("static/dt.html");
    private static final MediaType HTML = new MediaType(MediaType.TEXT_HTML, java.nio.charset.StandardCharsets.UTF_8);

    @GetMapping("/dt-demo.html")
    ResponseEntity<Resource> demo() {
        return ResponseEntity.ok().contentType(HTML).cacheControl(CacheControl.noCache()).body(PAGE);
    }
}
