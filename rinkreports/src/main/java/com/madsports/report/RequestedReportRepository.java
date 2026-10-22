package com.madsports.report;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;

public interface RequestedReportRepository extends JpaRepository<RequestedReport, Long> {

    /** Informes que pidió el usuario desde una fecha (para el cupo del mes). */
    long countByUserIdAndCreatedAtGreaterThanEqual(Long userId, Instant from);
}
