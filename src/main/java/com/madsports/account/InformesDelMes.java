package com.madsports.account;

import java.util.Map;

/**
 * Los informes del mes de un usuario, para la sesión ({@code informesMes} de {@code /api/sesion}). Lo aporta el
 * producto que tiene cupo de informes (RinkReports); la cuenta no depende de él.
 */
public interface InformesDelMes {

    /** {usados, limite (0 = sin límite), renueva (fecha ISO)}. */
    Map<String, Object> de(Usuario u);
}
