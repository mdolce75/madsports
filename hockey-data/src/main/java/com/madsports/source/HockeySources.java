package com.madsports.source;

import com.madsports.sidgad.model.Federation;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Las fuentes de datos registradas; cada federación usa la suya. */
@Component
public class HockeySources {

    private final Map<DataSource, HockeySource> byKind = new EnumMap<>(DataSource.class);

    public HockeySources(List<HockeySource> sources) {
        sources.forEach(s -> byKind.put(s.kind(), s));
    }

    /** Para los tests: las fuentes dadas. */
    public static HockeySources of(HockeySource... sources) {
        return new HockeySources(Arrays.asList(sources));
    }

    /** La fuente de la federación. */
    public HockeySource of(Federation f) {
        HockeySource s = byKind.get(f.source());
        if (s == null) {
            throw new IllegalStateException("No hay fuente de datos " + f.source() + " para la " + f.name() + ".");
        }
        return s;
    }
}
