package com.madsports.config;

import com.madsports.catalog.EnabledFederations;
import com.madsports.report.ReportFederations;
import com.madsports.sidgad.model.Federation;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** La AMP apagada (como en la app hasta nuevo aviso): ni en el generador, ni en los catálogos, ni en el registro. */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:ampoff;DB_CLOSE_DELAY=-1", "madsports.amp.enabled=false"})
@AutoConfigureMockMvc
class AmpOffTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ReportFederations federations;

    @Test
    void onlyTheFppIsOffered() {
        assertThat(federations.list()).containsExactly(Federation.FPP);
        assertThat(federations.enabled("FPP")).isEqualTo(Federation.FPP);
        assertThatThrownBy(() -> federations.enabled("AMP")).hasMessage("La AMP todavía no está disponible.");
    }

    @Test
    void theAmpCatalogIsRefusedAndTheSignupDoesNotOfferIt() throws Exception {
        mvc.perform(get("/api/catalog").param("federation", "AMP")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/signup/options")).andExpect(jsonPath("$.amp").value(false));
    }

    @Test
    void turnedOnBothAreEnabled() {
        assertThat(new EnabledFederations(true).list()).containsExactly(Federation.FPP, Federation.AMP);
        assertThat(new EnabledFederations(false).enabled(Federation.AMP)).isFalse();
    }
}
