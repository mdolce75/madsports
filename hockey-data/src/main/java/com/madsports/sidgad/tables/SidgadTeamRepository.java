package com.madsports.sidgad.tables;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SidgadTeamRepository extends JpaRepository<SidgadTeam, Long> {

    List<SidgadTeam> findByCompetitionId(int competitionId);

    List<SidgadTeam> findByClubId(int clubId);

    void deleteByCompetitionId(int competitionId);
}
