package com.madsports.sidgad.tables;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SidgadStandingRepository extends JpaRepository<SidgadStanding, Long> {

    List<SidgadStanding> findByCompetitionIdOrderByGroupNameAscPositionAsc(int competitionId);

    void deleteByCompetitionId(int competitionId);
}
