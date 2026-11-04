package com.madsports.report;

import com.madsports.account.UserAccount;
import com.madsports.catalog.EnabledFederations;
import com.madsports.sidgad.model.Federation;
import org.springframework.stereotype.Component;

import java.util.List;

/** Federaciones que se ofrecen en el generador de informes: las habilitadas (la FPP, y la AMP si está encendida). */
@Component
public class ReportFederations {

    private final EnabledFederations enabled;

    public ReportFederations(EnabledFederations enabled) {
        this.enabled = enabled;
    }

    /** Una federación del combo: "FPP", "Federación Porteña de Patín". */
    public record Option(String code, String name) {
    }

    public List<Federation> list() {
        return enabled.list();
    }

    /** La federación de un catálogo o un plantel ("FPP", "AMP"; vacía = FPP), si está habilitada. */
    public Federation enabled(String code) {
        return enabled.check(Federation.parse(code));
    }

    public List<Option> options() {
        return list().stream().map(f -> new Option(f.name(), f.fullName())).toList();
    }

    /**
     * La federación pedida ("FPP", "AMP"), o la del perfil si no viene. Tiene que ser una de las habilitadas: con la
     * AMP apagada no hay datos guardados, ni para las cuentas de la AMP.
     */
    public Federation resolve(String code, UserAccount u) {
        Federation f = code == null || code.isBlank() ? u.federation() : Federation.parse(code);
        if (!list().contains(f)) {
            throw new IllegalArgumentException("Los informes de la " + f.name() + " todavía no están disponibles.");
        }
        return f;
    }
}
