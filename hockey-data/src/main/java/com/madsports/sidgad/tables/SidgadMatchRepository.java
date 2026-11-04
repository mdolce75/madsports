package com.madsports.sidgad.tables;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SidgadMatchRepository extends JpaRepository<SidgadMatch, Long> {

    List<SidgadMatch> findByCompetitionIdOrderByRoundAscMatchDateAsc(int competitionId);

    void deleteByCompetitionId(int competitionId);
}
