package com.madsports.catalog;

import com.madsports.sidgad.model.Federation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Las federaciones habilitadas. La FPP, siempre. La AMP, solo con {@code madsports.amp.enabled} ({@code AMP_ENABLED});
 * apagada, no se sincroniza ni se chequea, y no aparece en el generador, los catálogos, los planteles ni el registro.
 */
@Component
public class EnabledFederations {

    private final boolean amp;

    public EnabledFederations(@Value("${madsports.amp.enabled:false}") boolean amp) {
        this.amp = amp;
    }

    public List<Federation> list() {
        return amp ? List.of(Federation.FPP, Federation.AMP) : List.of(Federation.FPP);
    }

    public boolean enabled(Federation f) {
        return list().contains(f);
    }

    /** La federación, si está habilitada; si no, el aviso de que todavía no está disponible. */
    public Federation check(Federation f) {
        if (!enabled(f)) {
            throw new IllegalArgumentException("La " + f.name() + " todavía no está disponible.");
        }
        return f;
    }
}
