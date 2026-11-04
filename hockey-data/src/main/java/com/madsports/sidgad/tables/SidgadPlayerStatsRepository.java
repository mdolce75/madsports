package com.madsports.sidgad.tables;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SidgadPlayerStatsRepository extends JpaRepository<SidgadPlayerStats, Long> {

    List<SidgadPlayerStats> findByCompetitionIdOrderByGoalsDesc(int competitionId);

    void deleteByCompetitionId(int competitionId);
}
