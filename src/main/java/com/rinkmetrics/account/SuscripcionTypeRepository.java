package com.rinkmetrics.account;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SuscripcionTypeRepository extends JpaRepository<SuscripcionType, Long> {

    Optional<SuscripcionType> findByCodigo(String codigo);
}
