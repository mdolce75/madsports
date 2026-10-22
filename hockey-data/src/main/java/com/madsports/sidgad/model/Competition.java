package com.madsports.sidgad.model;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Una competición del catálogo, p. ej. "TORNEO CLAUSURA PRIMERA MASCULINO (FPP)" (idc 779).
 * La categoría y el torneo se derivan del nombre; si el nombre no sigue el patrón FPP,
 * {@link #category()} es {@code null} y la competición se ignora.
 */
public record Competition(int id, String name, int federationId, int seasonId,
                          Tournament tournament, Category category, int division,
                          List<Models.TeamRef> teams, int periodMinutes) {

    /** Duración de período si el catálogo no la informa ({@code tpo_reg} en config_params). */
    public static final int DEFAULT_PERIOD_MINUTES = 25;

    public enum Tournament {
        OPENING, CLOSING;

        public String label() {
            return this == OPENING ? "Apertura" : "Clausura";
        }

        /** Como figura en los nombres de competición de SIDGAD: "APERTURA" o "CLAUSURA". */
        public String sidgadName() {
            return this == OPENING ? "APERTURA" : "CLAUSURA";
        }

        /** El torneo de un nombre de SIDGAD ("APERTURA", "CLAUSURA"). */
        public static Tournament fromSidgad(String name) {
            return switch (name.trim().toUpperCase()) {
                case "APERTURA" -> OPENING;
                case "CLAUSURA" -> CLOSING;
                default -> throw new IllegalArgumentException("Torneo desconocido: " + name);
            };
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

    /** FPP: "TORNEO CLAUSURA PRIMERA MASCULINO (FPP)", "TORNEO APERTURA JUNIORS FEMENINO 2 (FPP)". */
    private static final Pattern NAME = Pattern.compile(
            "TORNEO\\s+(APERTURA|CLAUSURA)\\s+(.+?)\\s+(MASCULINO|FEMENINO)(?:\\s+(\\d+))?\\b");

    /**
     * AMP (Mendoza): {@code AMP'26- CAMP MASC "APERTURA 26"-CATEGORIA SENIOR}; el prefijo y los espacios varían
     * ("C MASC", "CAMP MASC\"CLAUSURA 26\"CATEGORIA PREINFANTIL", "CAMP FEMEN").
     */
    private static final Pattern AMP_NAME = Pattern.compile(
            "\\b(MASC|FEMEN)[A-Z]*\\W*(APERTURA|CLAUSURA)\\b.*?CATEGOR[IÍ]A\\s+([A-ZÑ]+)");

    /** Interpreta el nombre de la competición; si no es de un formato conocido, la categoría queda {@code null}. */
    public static Competition of(int id, String name, int federationId, int seasonId, List<Models.TeamRef> teams,
                                 int periodMinutes) {
        int minutes = periodMinutes > 0 ? periodMinutes : DEFAULT_PERIOD_MINUTES;
        String upper = name.toUpperCase();
        Matcher m = NAME.matcher(upper);
        if (m.find()) {
            int division = m.group(4) == null ? 1 : Integer.parseInt(m.group(4));
            return new Competition(id, name, federationId, seasonId, Tournament.fromSidgad(m.group(1)),
                    new Category(m.group(2).trim(), m.group(3)), division, teams, minutes);
        }
        Matcher amp = AMP_NAME.matcher(upper);
        if (amp.find()) {
            String branch = amp.group(1).equals("MASC") ? "MASCULINO" : "FEMENINO";
            return new Competition(id, name, federationId, seasonId, Tournament.fromSidgad(amp.group(2)),
                    new Category(amp.group(3), branch), 1, teams, minutes);
        }
        return new Competition(id, name, federationId, seasonId, null, null, 1, teams, minutes);
    }

    /** La federación de la competición (null si no se releva). */
    public Federation federation() {
        return Federation.of(federationId);
    }

    public String tournamentLabel() {
        String base = tournament == null ? name : tournament.label();
        return division > 1 ? base + " (Div. " + division + ")" : base;
    }

    public String url() {
        return "https://www.buenosaires.sidgad.com/league/" + id;
    }
}
