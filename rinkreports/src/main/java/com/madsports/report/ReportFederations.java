package com.madsports.report;

import com.madsports.account.UserAccount;
import com.madsports.sidgad.model.Federation;
import org.springframework.stereotype.Component;

import java.util.List;

/** Federaciones que se ofrecen en el generador de informes: la FPP y la AMP, siempre. */
@Component
public class ReportFederations {

    /** Una federación del combo: "FPP", "Federación Porteña de Patín". */
    public record Option(String code, String name) {
    }

    public List<Federation> list() {
        return List.of(Federation.FPP, Federation.AMP);
    }

    public List<Option> options() {
        return list().stream().map(f -> new Option(f.name(), f.fullName())).toList();
    }

    /**
     * La federación pedida ("FPP", "AMP"), o la del perfil si no viene. Tiene que ser una de las ofrecidas, o la del
     * perfil del usuario (las cuentas de la AMP de antes siguen pidiendo sus informes).
     */
    public Federation resolve(String code, UserAccount u) {
        Federation f = code == null || code.isBlank() ? u.federation() : Federation.parse(code);
        if (!list().contains(f) && u.getProfiles().stream().noneMatch(p -> p.federation() == f)) {
            throw new IllegalArgumentException("Los informes de la " + f.name() + " todavía no están disponibles.");
        }
        return f;
    }
}
