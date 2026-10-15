package com.rinkmetrics.account;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;

/** Tipo de suscripción: mensual, trimestral, semestral o anual (tabla {@code suscripcion_type}). */
@Entity
@Table(name = "suscripcion_type")
public class SuscripcionType {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** MENSUAL, TRIMESTRAL, SEMESTRAL o ANUAL. */
    private String codigo;
    private String nombre;
    /** Duración en meses (1, 3, 6 o 12). */
    private int meses;

    protected SuscripcionType() {
    }

    public SuscripcionType(String codigo, String nombre, int meses) {
        this.codigo = codigo;
        this.nombre = nombre;
        this.meses = meses;
    }

    /** Último día de una suscripción de este tipo que empieza en {@code desde}. */
    public LocalDate hasta(LocalDate desde) {
        return desde.plusMonths(meses).minusDays(1);
    }

    public Long getId() { return id; }
    public String getCodigo() { return codigo; }
    public String getNombre() { return nombre; }
    public int getMeses() { return meses; }
}
