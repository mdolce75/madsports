package com.madsports.report;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface RequestedReportRepository extends JpaRepository<RequestedReport, Long> {

    /** Informes que pidió el usuario desde una fecha (para el cupo del mes). */
    long countByUserIdAndCreatedAtGreaterThanEqual(Long userId, Instant from);

    /** Los últimos informes que pidió el usuario, del más nuevo al más viejo (su historial). */
    List<RequestedReport> findTop50ByUserIdOrderByCreatedAtDesc(Long userId);
}
