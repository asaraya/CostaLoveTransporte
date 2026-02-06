package com.cargosfsr.inventario.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

import com.cargosfsr.inventario.model.Saco;

public interface SacoRepository extends JpaRepository<Saco, Long> {
    Optional<Saco> findByMarchamo(String marchamo);

    @Modifying
    @Transactional
    void deleteByMarchamo(String marchamo);
}
