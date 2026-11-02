package com.madsports.sidgad;

import com.madsports.sidgad.model.Competition;
import com.madsports.sidgad.model.Models.TeamRef;
import com.madsports.sidgad.parse.SidgadParser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Qué cambió entre dos copias del catálogo de SIDGAD ({@code cap_ls_1.php}): competiciones que entran o salen y
 * equipos que entran o salen de cada una. Lo demás del HTML (orden, adornos) no cuenta.
 */
final class CatalogChanges {

    private CatalogChanges() {
    }

    /** Los cambios, uno por renglón ("Competición nueva: …", "En …: entra …"); vacía si no cambió nada. */
    static List<String> between(String before, String after) {
        Map<Integer, Competition> old = byId(SidgadParser.catalog(before));
        Map<Integer, Competition> now = byId(SidgadParser.catalog(after));
        List<String> out = new ArrayList<>();
        now.values().stream().filter(c -> !old.containsKey(c.id()))
                .forEach(c -> out.add("Competición nueva: " + c.name() + " (" + c.id() + ")"));
        old.values().stream().filter(c -> !now.containsKey(c.id()))
                .forEach(c -> out.add("Ya no está: " + c.name() + " (" + c.id() + ")"));
        for (Competition c : now.values()) {
            Competition prev = old.get(c.id());
            if (prev == null) {
                continue;
            }
            Map<Integer, TeamRef> was = teams(prev);
            Map<Integer, TeamRef> is = teams(c);
            is.values().stream().filter(t -> !was.containsKey(t.teamId()))
                    .forEach(t -> out.add("En " + c.name() + ": entra " + t.name()));
            was.values().stream().filter(t -> !is.containsKey(t.teamId()))
                    .forEach(t -> out.add("En " + c.name() + ": sale " + t.name()));
        }
        return out;
    }

    private static Map<Integer, Competition> byId(List<Competition> all) {
        return all.stream().collect(Collectors.toMap(Competition::id, Function.identity(), (a, b) -> a,
                LinkedHashMap::new));
    }

    private static Map<Integer, TeamRef> teams(Competition c) {
        return c.teams().stream().collect(Collectors.toMap(TeamRef::teamId, Function.identity(), (a, b) -> a,
                LinkedHashMap::new));
    }
}
