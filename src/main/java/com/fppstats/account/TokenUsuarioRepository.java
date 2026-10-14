package com.fppstats.account;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;

public interface TokenUsuarioRepository extends JpaRepository<TokenUsuario, Long> {

    Optional<TokenUsuario> findByHashAndTipo(String hash, TokenUsuario.Tipo tipo);

    /** Para no mandar más de un mail del mismo tipo por minuto al mismo usuario. */
    boolean existsByUsuarioAndTipoAndCreadoAfter(Usuario usuario, TokenUsuario.Tipo tipo, Instant desde);
}
