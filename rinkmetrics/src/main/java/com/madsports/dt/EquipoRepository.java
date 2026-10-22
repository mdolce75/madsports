package com.madsports.dt;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EquipoRepository extends JpaRepository<Equipo, Long> {

    /** Equipos del DT, en el orden en que los creó. */
    List<Equipo> findByUsuarioIdOrderByIdAsc(Long usuarioId);

    Optional<Equipo> findByIdAndUsuarioId(Long id, Long usuarioId);

    boolean existsByUsuarioIdAndNombreIgnoreCase(Long usuarioId, String nombre);
}
