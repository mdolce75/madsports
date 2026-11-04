package com.madsports.sidgad.tables;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SidgadPlayerSeasonMatchRepository extends JpaRepository<SidgadPlayerSeasonMatch, Long> {

    List<SidgadPlayerSeasonMatch> findByPlayerIdAndSeasonIdOrderByMatchDateAsc(int playerId, int seasonId);

    void deleteByPlayerIdAndSeasonId(int playerId, int seasonId);
}
