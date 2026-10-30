package com.madsports.sidgad.model;

import com.madsports.source.DataSource;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
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

    /**
     * Apertura y Clausura (los que se eligen como período), Anual, y las copas interprovinciales de clubes (Copa
     * Nacional) u otros torneos, que se muestran con su nombre ({@link #tournamentLabel()}).
     */
    public enum Tournament {
        OPENING, CLOSING, ANNUAL, CUP, OTHER;

        public String label() {
            return switch (this) {
                case OPENING -> "Apertura";
                case CLOSING -> "Clausura";
                case ANNUAL -> "Anual";
                case CUP -> "Copa";
                case OTHER -> "Otro";
            };
        }

        /** Como figura en los nombres de competición: "APERTURA", "CLAUSURA", "ANUAL". */
        public String sidgadName() {
            return switch (this) {
                case OPENING -> "APERTURA";
                case CLOSING -> "CLAUSURA";
                case ANNUAL -> "ANUAL";
                default -> name();
            };
        }

        /** El torneo de un nombre de competición ("APERTURA", "CLAUSURA", "ANUAL"). */
        public static Tournament fromSidgad(String name) {
            return switch (name.trim().toUpperCase()) {
                case "APERTURA" -> OPENING;
                case "CLAUSURA" -> CLOSING;
                case "ANUAL" -> ANNUAL;
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

        /** "Primera A1", "Infantil": cada palabra con mayúscula inicial. */
        public String levelLabel() {
            return capitalize(level);
        }

        public String branchLabel() {
            return capitalize(branch);
        }

        private static String capitalize(String s) {
            StringBuilder sb = new StringBuilder();
            for (String w : s.trim().split("\\s+")) {
                sb.append(sb.isEmpty() ? "" : " ").append(w.charAt(0)).append(w.substring(1).toLowerCase());
            }
            return sb.toString();
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

    /**
     * FSP (San Juan): "A1 PRIMERA TORNEO APERTURA 2026", "A2  PRIMERA TORNEO …" (con espacios de más). La división
     * va en el nivel ("PRIMERA A1"): Primera A1 y Segunda A1 son categorías distintas.
     */
    private static final Pattern FSP_NAME = Pattern.compile(
            "^(A\\d)\\s+(PRIMERA|SEGUNDA)\\s+TORNEO\\s+(APERTURA|CLAUSURA|ANUAL)\\b");

    /** FSP femenino: "FEMENINO SEGUNDA TORNEO CLAUSURA 2026". */
    private static final Pattern FSP_WOMEN = Pattern.compile(
            "^FEMENINO\\s+(PRIMERA|SEGUNDA)\\s+TORNEO\\s+(APERTURA|CLAUSURA|ANUAL)\\b");

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
        Matcher fsp = FSP_NAME.matcher(upper.trim());
        if (fsp.find()) {
            return new Competition(id, name, federationId, seasonId, Tournament.fromSidgad(fsp.group(3)),
                    new Category(fsp.group(2) + " " + fsp.group(1), "MASCULINO"), 1, teams, minutes);
        }
        Matcher women = FSP_WOMEN.matcher(upper.trim());
        if (women.find()) {
            return new Competition(id, name, federationId, seasonId, Tournament.fromSidgad(women.group(2)),
                    new Category(women.group(1), "FEMENINO"), 1, teams, minutes);
        }
        Matcher amp = AMP_NAME.matcher(upper);
        if (amp.find()) {
            String branch = amp.group(1).equals("MASC") ? "MASCULINO" : "FEMENINO";
            return new Competition(id, name, federationId, seasonId, Tournament.fromSidgad(amp.group(2)),
                    new Category(amp.group(3), branch), 1, teams, minutes);
        }
        return new Competition(id, name, federationId, seasonId, null, null, 1, teams, minutes);
    }

    /**
     * Una copa interprovincial de clubes (Copa Nacional): sus partidos no traen categoría, va la que diga la
     * federación ({@link Federation#cupCategory()}).
     */
    public static Competition cup(int id, String name, int federationId, int seasonId, Category category,
                                  List<Models.TeamRef> teams, int periodMinutes) {
        return new Competition(id, name, federationId, seasonId, Tournament.CUP, category, 1, teams,
                periodMinutes > 0 ? periodMinutes : DEFAULT_PERIOD_MINUTES);
    }

    /** La federación de la competición (null si no se releva). */
    public Federation federation() {
        return Federation.of(federationId);
    }

    /**
     * "Apertura", "Clausura" o, si es la segunda del año ("... FEMENINO 2"), con su número: "Apertura 2". Una copa u
     * otro torneo, con su nombre: "Copa Nacional de Clubes".
     */
    public String tournamentLabel() {
        String base = tournament == null ? name
                : tournament == Tournament.CUP || tournament == Tournament.OTHER ? displayName(name) : tournament.label();
        return division > 1 ? base + " " + division : base;
    }

    /**
     * El nombre del torneo entre las competiciones del informe: si la categoría tiene dos del mismo torneo ("TORNEO
     * APERTURA JUNIORS FEMENINO" y "... FEMENINO 2"), cada una con su número: "Apertura 1" y "Apertura 2".
     */
    public String tournamentLabel(Collection<Competition> all) {
        boolean twice = tournament != null && all.stream().anyMatch(o -> o.id() != id
                && o.tournament() == tournament && Objects.equals(o.category(), category));
        return twice && division == 1 ? tournament.label() + " 1" : tournamentLabel();
    }

    private static final Set<String> LOWERCASE = Set.of("de", "del", "la", "las", "los", "y", "e");

    /** "COPA NACIONAL DE CLUBES" → "Copa Nacional de Clubes". */
    static String displayName(String name) {
        StringBuilder sb = new StringBuilder();
        for (String w : name.trim().toLowerCase().split("\\s+")) {
            if (w.isEmpty()) {
                continue;
            }
            boolean keep = !sb.isEmpty() && LOWERCASE.contains(w);
            sb.append(sb.isEmpty() ? "" : " ").append(keep ? w : Character.toUpperCase(w.charAt(0)) + w.substring(1));
        }
        return sb.toString();
    }

    /** Página pública de la competición en la fuente de su federación. */
    public String url() {
        Federation f = federation();
        return (f == null ? DataSource.SIDGAD : f.source()).competitionUrl(id);
    }
}
