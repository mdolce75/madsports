package com.madsports.report;

import com.madsports.account.UserAccount;
import com.madsports.sidgad.model.Federation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Federaciones que se ofrecen en el generador de informes: la FPP y la FSP siempre; la AMP, con
 * {@code madsports.signup.amp} (oculta hasta nuevo aviso, como en el registro).
 */
@Component
public class ReportFederations {

    /** Una federación del combo: "FPP", "Federación Porteña de Patín". */
    public record Option(String code, String name) {
    }

    private final boolean amp;

    public ReportFederations(@Value("${madsports.signup.amp:false}") boolean amp) {
        this.amp = amp;
    }

    public List<Federation> list() {
        return amp ? List.of(Federation.FPP, Federation.AMP, Federation.FSP) : List.of(Federation.FPP, Federation.FSP);
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
