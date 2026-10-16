package com.rinkmetrics.account;

import jakarta.persistence.Embeddable;

/**
 * Un equipo del DT dentro de su club (tabla {@code usuario_equipo_dt}).
 *
 * @param categoria categoría con la letra del equipo si el club tiene más de uno ("JUVENIL:A", o "PRIMERA")
 * @param rama      MASCULINO o FEMENINO
 */
@Embeddable
public record EquipoDt(String categoria, String rama) {
}
