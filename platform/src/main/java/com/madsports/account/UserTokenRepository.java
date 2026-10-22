package com.madsports.account;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;

public interface UserTokenRepository extends JpaRepository<UserToken, Long> {

    Optional<UserToken> findByHashAndKind(String hash, UserToken.Kind kind);

    /** Para no mandar más de un mail del mismo tipo por minuto al mismo usuario. */
    boolean existsByUserAndKindAndCreatedAtAfter(UserAccount user, UserToken.Kind kind, Instant from);
}
