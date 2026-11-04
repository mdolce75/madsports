package com.madsports.sidgad.tables;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SidgadMatchLineupRepository extends JpaRepository<SidgadMatchLineup, Long> {

    List<SidgadMatchLineup> findByIdp(int idp);

    List<SidgadMatchLineup> findByPlayerId(Integer playerId);

    void deleteByIdp(int idp);
}
