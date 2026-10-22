package com.madsports.account;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SubscriptionPlanRepository extends JpaRepository<SubscriptionPlan, Long> {

    Optional<SubscriptionPlan> findByCode(String code);

    /** El plan mensual de un tipo de usuario (PLAYER, CLUB o DT). */
    /** Planes del tipo de usuario, del más barato al más caro (el free primero). */
    List<SubscriptionPlan> findByUserTypeOrderByPriceUsdAscIdAsc(String userType);
}
