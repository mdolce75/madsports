package com.madsports.account;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {

    Optional<UserAccount> findByUsername(String username);

    boolean existsByUsername(String username);

    /** El email es único dentro de cada producto (el mismo puede tener una cuenta en RinkReports y otra en RinkMetrics). */
    boolean existsByEmailIgnoreCaseAndProduct(String email, Product product);

    Optional<UserAccount> findByEmailIgnoreCaseAndProduct(String email, Product product);

    /** Las cuentas con ese email, una por producto como mucho. */
    List<UserAccount> findByEmailIgnoreCaseOrderByIdAsc(String email);

    boolean existsByPlayerId(int playerId);

    /** Suma un informe pedido. Un solo UPDATE en la base: dos pedidos simultáneos no se pisan. */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update Usuario u set u.informesPedidos = u.informesPedidos + 1 where u.usuario = :usuario")
    int countReport(@Param("usuario") String username);
}
