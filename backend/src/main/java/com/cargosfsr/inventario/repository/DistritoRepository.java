package com.cargosfsr.inventario.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.cargosfsr.inventario.model.Distrito;

public interface DistritoRepository extends JpaRepository<Distrito, Long> {

    Optional<Distrito> findByNombre(String nombre);

    boolean existsByNombre(String nombre);

    List<Distrito> findByActivoTrueOrderByNombreAsc();

    List<Distrito> findByNombreContainingIgnoreCaseAndActivoTrueOrderByNombreAsc(String q);
}
