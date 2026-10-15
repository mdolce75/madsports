package com.rinkmetrics.account;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SuscripcionTypeRepository extends JpaRepository<SuscripcionType, Long> {

    Optional<SuscripcionType> findByCodigo(String codigo);

    /** Planes de un tipo de usuario (PLAYER o CLUB), del más corto al más largo. */
    List<SuscripcionType> findByTipoUsuarioOrderByMeses(String tipoUsuario);
}
