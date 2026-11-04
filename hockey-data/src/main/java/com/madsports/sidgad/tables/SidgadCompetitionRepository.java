package com.madsports.sidgad.tables;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SidgadCompetitionRepository extends JpaRepository<SidgadCompetition, Integer> {

    List<SidgadCompetition> findByFederationAndSeasonYear(String federation, Integer seasonYear);
}
