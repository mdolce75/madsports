package com.madsports.source;

/**
 * De dónde salen los datos de una federación ({@link com.madsports.sidgad.model.Federation#source()}), con el nombre
 * que se muestra en los informes ("Datos: FPP (SIDGAD)", "SIDGAD no publicó…") y el enlace de cada competición.
 */
public enum DataSource {
    /** server2.sidgad.es (FPP y AMP). */
    SIDGAD("SIDGAD"),
    /** gestiondepsj.com.ar, API pública (FSP y UEHP). */
    PSJ("gestiondepsj");

    private final String label;

    DataSource(String label) {
        this.label = label;
    }

    /** Como se nombra en los informes. */
    public String label() {
        return label;
    }

    /** ¿Publica penales y libres directos? (gestiondepsj no los distingue de los goles.) */
    public boolean setPieces() {
        return this == SIDGAD;
    }

    /** ¿Las planillas marcan a los titulares (el cinco inicial)? Las de gestiondepsj, no. */
    public boolean starters() {
        return this == SIDGAD;
    }

    /**
     * ¿La ficha de temporada del jugador lista todos sus partidos? La de gestiondepsj, solo en los que tuvo algún
     * evento: un partido sin acta que no figura ahí puede haberlo jugado.
     */
    public boolean fullSeason() {
        return this == SIDGAD;
    }

    /** Página pública de la competición; gestiondepsj no tiene una por torneo: su sitio. */
    public String competitionUrl(int id) {
        return switch (this) {
            case SIDGAD -> "https://www.buenosaires.sidgad.com/league/" + id;
            case PSJ -> "https://gestiondepsj.com.ar/";
        };
    }
}
