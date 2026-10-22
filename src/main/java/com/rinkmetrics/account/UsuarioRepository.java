package com.rinkmetrics.account;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface UsuarioRepository extends JpaRepository<Usuario, Long> {

    Optional<Usuario> findByUsuario(String usuario);

    boolean existsByUsuario(String usuario);

    /** El email es único dentro de cada producto (el mismo puede tener una cuenta en RinkReports y otra en RinkMetrics). */
    boolean existsByEmailIgnoreCaseAndProducto(String email, Producto producto);

    Optional<Usuario> findByEmailIgnoreCaseAndProducto(String email, Producto producto);

    /** Las cuentas con ese email, una por producto como mucho. */
    List<Usuario> findByEmailIgnoreCaseOrderByIdAsc(String email);

    boolean existsByJugadorId(int jugadorId);

    /** Suma un informe pedido. Un solo UPDATE en la base: dos pedidos simultáneos no se pisan. */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update Usuario u set u.informesPedidos = u.informesPedidos + 1 where u.usuario = :usuario")
    int contarInforme(@Param("usuario") String usuario);
}
