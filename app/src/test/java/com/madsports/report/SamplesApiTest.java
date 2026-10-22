package com.madsports.report;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Los informes de ejemplo de RinkReports, por la API de la aplicación: en pantalla, PDF y Excel, sin sesión. */
@SpringBootTest(properties = "madsports.ai.enabled=false")
@AutoConfigureMockMvc
class SamplesApiTest {

    @Autowired MockMvc mvc;

    @Test
    void samplesAreShownOnScreenAndExported() throws Exception {
        mvc.perform(get("/api/sample.html")).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(containsString("Primera Masculino")));
        mvc.perform(get("/api/sample-player.html")).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"));
        mvc.perform(get("/api/sample.xlsx")).andExpect(status().isOk())
                .andExpect(content().contentType(Downloads.XLSX))
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andExpect(header().string("Content-Disposition", containsString(".xlsx")));
        mvc.perform(get("/api/sample-player.xlsx")).andExpect(status().isOk())
                .andExpect(content().contentType(Downloads.XLSX));
    }
}
