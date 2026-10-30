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

    /** Página pública de la competición; gestiondepsj no tiene una por torneo: su sitio. */
    public String competitionUrl(int id) {
        return switch (this) {
            case SIDGAD -> "https://www.buenosaires.sidgad.com/league/" + id;
            case PSJ -> "https://gestiondepsj.com.ar/";
        };
    }
}
