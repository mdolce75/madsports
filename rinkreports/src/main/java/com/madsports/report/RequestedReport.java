package com.madsports.report;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Un informe que pidió el usuario (tabla {@code requested_report}): cuenta para el cupo del mes y queda en su historial
 * de informes generados, con la fecha y la hora y qué informe fue ({@code detail}: "Club completo · Apertura + Clausura
 * 2026").
 */
@Entity
@Table(name = "requested_report")
public class RequestedReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long userId;
    private Instant createdAt;
    /** Qué informe fue; vacío en los pedidos de antes del historial. */
    private String detail;
    /** Sus archivos (PDF, Excel y HTML) están guardados ({@link ReportArchive}); false si todavía no o si ya vencieron. */
    private boolean archived;
    /** Nombre del PDF para descargarlo ("informe-club-cavs-temporada-2026.pdf"). */
    private String fileName;
    /** Bytes del PDF y el Excel guardados. */
    private Long sizeBytes;

    protected RequestedReport() {
    }

    public RequestedReport(Long userId, Instant createdAt, String detail) {
        this.userId = userId;
        this.createdAt = createdAt;
        this.detail = detail;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public Instant getCreatedAt() { return createdAt; }
    public String getDetail() { return detail; }
    public boolean isArchived() { return archived; }
    public String getFileName() { return fileName; }
    public Long getSizeBytes() { return sizeBytes; }

    void archived(String fileName, long sizeBytes) {
        this.archived = true;
        this.fileName = fileName;
        this.sizeBytes = sizeBytes;
    }

    /** Sus archivos se borraron (pasó el tiempo que se conservan); el pedido queda en el historial. */
    void expired() {
        this.archived = false;
    }
}
