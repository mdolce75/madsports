package com.rinkmetrics.report;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;

public interface InformePedidoRepository extends JpaRepository<InformePedido, Long> {

    /** Informes que pidió el usuario desde una fecha (para el cupo del mes). */
    long countByUsuarioIdAndCreadoGreaterThanEqual(Long usuarioId, Instant desde);
}
