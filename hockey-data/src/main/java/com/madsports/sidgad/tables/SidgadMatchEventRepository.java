package com.madsports.sidgad.tables;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SidgadMatchEventRepository extends JpaRepository<SidgadMatchEvent, Long> {

    List<SidgadMatchEvent> findByIdp(int idp);

    void deleteByIdp(int idp);
}
