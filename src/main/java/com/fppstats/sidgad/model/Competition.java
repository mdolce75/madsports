package com.fppstats.sidgad.model;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Una competición del catálogo, p. ej. "TORNEO CLAUSURA PRIMERA MASCULINO (FPP)" (idc 779).
 * La categoría y el torneo se derivan del nombre; si el nombre no sigue el patrón FPP,
 * {@link #category()} es {@code null} y la competición se ignora.
 */
public record Competition(int id, String name, int federationId, int seasonId,
                          Torneo torneo, Category category, int division,
                          List<Models.TeamRef> teams) {

    public enum Torneo {
        APERTURA, CLAUSURA;

        public String label() {
            return this == APERTURA ? "Apertura" : "Clausura";
        }
    }

    /** Nivel + rama, p. ej. INFANTIL / FEMENINO. */
    public record Category(String level, String branch) {

        public String key() {
            return level + "_" + branch;
        }

        public String label() {
            return levelLabel() + " " + branchLabel();
        }

        public String levelLabel() {
            return capitalize(level);
        }

        public String branchLabel() {
            return capitalize(branch);
        }

        private static String capitalize(String s) {
            return s.charAt(0) + s.substring(1).toLowerCase();
        }
    }

    private static final Pattern NAME = Pattern.compile(
            "TORNEO\\s+(APERTURA|CLAUSURA)\\s+(.+?)\\s+(MASCULINO|FEMENINO)(?:\\s+(\\d+))?\\b");

    /** Interpreta el nombre de la competición; devuelve {@code null} si no es del formato FPP. */
    public static Competition of(int id, String name, int federationId, int seasonId, List<Models.TeamRef> teams) {
        Matcher m = NAME.matcher(name.toUpperCase());
        if (!m.find()) {
            return new Competition(id, name, federationId, seasonId, null, null, 1, teams);
        }
        int division = m.group(4) == null ? 1 : Integer.parseInt(m.group(4));
        return new Competition(id, name, federationId, seasonId, Torneo.valueOf(m.group(1)),
                new Category(m.group(2).trim(), m.group(3)), division, teams);
    }

    public String torneoLabel() {
        String base = torneo == null ? name : torneo.label();
        return division > 1 ? base + " (Div. " + division + ")" : base;
    }

    public String url() {
        return "https://www.buenosaires.sidgad.com/league/" + id;
    }
}
