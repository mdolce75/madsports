package com.fppstats.stats;

import com.fppstats.sidgad.model.Models.TeamRef;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Identifica los equipos de un club. Un club puede tener equipo A y B en la misma categoría y rama: son equipos
 * distintos y nunca se agrupan. SIDGAD los nombra de forma irregular, por ejemplo (catálogo 2026):
 * "CAVS A"/"CAVS B", "CAVS"/"CAVS B", "CAVSB", mismo código "CAVS" con nombres "VELEZ A"/"VELEZ B", o
 * "VELEZ SARSFIELD B". Además puede asignar el logo del club a un equipo de otro club (Comunicaciones con el logo de
 * Vélez en Juniors Masculino Clausura): ese equipo no es del club.
 */
public final class TeamKeys {

    private static final Pattern SPACED_LETTER = Pattern.compile("\\s([A-D])$");

    private TeamKeys() {
    }

    /** ¿El equipo es realmente del club? (código o nombre del club, no solo el logo). */
    public static boolean belongs(TeamRef t, String clubCode, String clubName) {
        String code = norm(t.code());
        String name = norm(t.name());
        return code.startsWith(norm(clubCode)) || name.startsWith(norm(clubName));
    }

    /** Igual que {@link #keys(List, String, boolean)}, con letras solo si en esta competición hay más de un equipo. */
    public static Map<Integer, String> keys(List<TeamRef> own, String clubCode) {
        return keys(own, clubCode, own.size() > 1);
    }

    /**
     * Clave de cada equipo del club en una competición: código del club + letra ("CAVS A", "CAVS B") o solo el código.
     * Los equipos sin letra reciben la primera letra libre: dos equipos nunca comparten clave.
     *
     * @param own          equipos del club en la competición (ya filtrados con {@link #belongs})
     * @param severalTeams true si el club tiene más de un equipo en la categoría y rama en algún torneo del año.
     *                     Si es false, el equipo es único y va sin letra aunque SIDGAD lo nombre "A" (Vélez en
     *                     Prejuvenil Femenino: "VELEZ A" en el Apertura y "VELEZ" en el Clausura).
     */
    public static Map<Integer, String> keys(List<TeamRef> own, String clubCode, boolean severalTeams) {
        String base = norm(clubCode);
        Map<Integer, String> keys = new LinkedHashMap<>();
        if (!severalTeams) {
            own.forEach(t -> keys.put(t.teamId(), base));
            return keys;
        }
        List<String> used = new ArrayList<>();
        List<TeamRef> unlettered = new ArrayList<>();
        for (TeamRef t : own.stream().sorted(Comparator.comparingInt(TeamRef::teamId)).toList()) {
            String l = letter(t, base);
            if (l == null || used.contains(l)) {
                unlettered.add(t);
            } else {
                used.add(l);
                keys.put(t.teamId(), base + " " + l);
            }
        }
        for (TeamRef t : unlettered) {
            String free = "ABCDEFGH".chars().mapToObj(c -> String.valueOf((char) c))
                    .filter(l -> !used.contains(l)).findFirst().orElseThrow();
            used.add(free);
            keys.put(t.teamId(), base + " " + free);
        }
        return keys;
    }

    /** Letra del equipo (A-D) en el código ("CAVS B", "CAVSB") o en el nombre ("VELEZ B"); null si no tiene. */
    static String letter(TeamRef t, String clubCode) {
        String code = norm(t.code());
        Matcher m = SPACED_LETTER.matcher(code);
        if (m.find()) {
            return m.group(1);
        }
        if (code.length() == clubCode.length() + 1 && code.startsWith(clubCode) && code.matches(".*[A-D]$")) {
            return code.substring(code.length() - 1);
        }
        Matcher n = SPACED_LETTER.matcher(norm(t.name()));
        return n.find() ? n.group(1) : null;
    }

    /** Nombre para mostrar: "VELEZ B" a partir del nombre del club y la clave "CAVS B". */
    public static String displayName(String clubName, String clubCode, String key) {
        String suffix = key.length() > clubCode.length() ? key.substring(clubCode.length()).trim() : "";
        return suffix.isEmpty() ? clubName : clubName + " " + suffix;
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }
}
