package com.madsports.account;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {

    /** El usuario es único dentro de cada producto (el mismo puede tener una cuenta en RinkReports y otra en RinkMetrics). */
    boolean existsByUsernameAndProduct(String username, Product product);

    Optional<UserAccount> findByUsernameAndProduct(String username, Product product);

    /** Las cuentas con ese usuario, una por producto como mucho. */
    List<UserAccount> findByUsernameOrderByIdAsc(String username);

    /** La cuenta con ese usuario si hay una sola (sin el producto no se sabe cuál es si tiene una en cada uno). */
    default Optional<UserAccount> findByUsername(String username) {
        List<UserAccount> found = findByUsernameOrderByIdAsc(username);
        return found.size() == 1 ? Optional.of(found.get(0)) : Optional.empty();
    }

    /**
     * La cuenta de un nombre de la sesión ({@link UserAccount#principal()}, "REPORTS:juan"). Si en ese producto no hay
     * una con ese usuario, o el nombre no trae producto, la única que haya con ese usuario: así el ingreso puede avisar
     * que la cuenta es del otro producto.
     */
    default Optional<UserAccount> findByPrincipal(String principal) {
        String name = principal == null ? "" : principal.trim();
        int colon = name.indexOf(':');
        if (colon < 0) {
            return findByUsername(name.toLowerCase(Locale.ROOT));
        }
        String username = name.substring(colon + 1).toLowerCase(Locale.ROOT);
        Product product;
        try {
            product = Product.valueOf(name.substring(0, colon));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        Optional<UserAccount> u = findByUsernameAndProduct(username, product);
        return u.isPresent() ? u : findByUsername(username);
    }

    /** El email es único dentro de cada producto (el mismo puede tener una cuenta en RinkReports y otra en RinkMetrics). */
    boolean existsByEmailIgnoreCaseAndProduct(String email, Product product);

    Optional<UserAccount> findByEmailIgnoreCaseAndProduct(String email, Product product);

    /** Las cuentas con ese email, una por producto como mucho. */
    List<UserAccount> findByEmailIgnoreCaseOrderByIdAsc(String email);

    boolean existsByPlayerId(int playerId);

    /** Suma un informe pedido. Un solo UPDATE en la base: dos pedidos simultáneos no se pisan. */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update UserAccount u set u.reportsRequested = u.reportsRequested + 1 where u.id = :id")
    int countReport(@Param("id") Long id);
}
