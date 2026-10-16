package com.rinkmetrics.account;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SuscripcionTypeRepository extends JpaRepository<SuscripcionType, Long> {

    Optional<SuscripcionType> findByCodigo(String codigo);

    /** El plan mensual de un tipo de usuario (PLAYER, CLUB o DT). */
    List<SuscripcionType> findByTipoUsuario(String tipoUsuario);
}
