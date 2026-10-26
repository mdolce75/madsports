package com.madsports.report;

/**
 * Un informe que se genera aparte (de club o de jugador): avisa cuando está listo, con su PDF, su Excel y el HTML para
 * verlo en pantalla. Así cada informe pedido se guarda en el historial del usuario ({@link ReportArchive}).
 */
public interface ReadyReport {

    /**
     * Corre la acción cuando el informe esté listo, antes de que se vea como listo (o en el momento, si ya lo está). Si
     * el informe falla, no corre.
     */
    void whenReady(Runnable action);

    byte[] pdf();

    byte[] xlsx();

    String html();

    /** "informe-club-cavs-temporada-2026.pdf" */
    String fileName();

    /** Corre una acción de informe listo sin cortar el informe si falla (queda en el log). */
    static void runQuietly(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(ReadyReport.class).warn("Al terminar el informe: {}", e.getMessage());
        }
    }
}
