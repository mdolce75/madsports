package com.madsports.account;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SuscripcionTypeRepository extends JpaRepository<SuscripcionType, Long> {

    Optional<SuscripcionType> findByCodigo(String codigo);

    /** El plan mensual de un tipo de usuario (PLAYER, CLUB o DT). */
    /** Planes del tipo de usuario, del más barato al más caro (el free primero). */
    List<SuscripcionType> findByTipoUsuarioOrderByPrecioUsdAscIdAsc(String tipoUsuario);
}
