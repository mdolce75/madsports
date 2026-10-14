package com.fppstats.account;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UsuarioRepository extends JpaRepository<Usuario, Long> {

    Optional<Usuario> findByUsuario(String usuario);

    boolean existsByUsuario(String usuario);

    boolean existsByEmailIgnoreCase(String email);

    boolean existsByDni(String dni);

    boolean existsByJugadorId(int jugadorId);
}
