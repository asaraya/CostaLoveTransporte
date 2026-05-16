package com.cargosfsr.inventario.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.cargosfsr.inventario.model.Ubicacion;
import com.cargosfsr.inventario.model.enums.UbicacionTipo;

public interface UbicacionRepository extends JpaRepository<Ubicacion, Long> {

    Optional<Ubicacion> findByCodigo(String codigo);

    // Listados para selector/búsqueda
    List<Ubicacion> findByActivoTrueOrderByCodigo();

    List<Ubicacion> findByTipoAndActivoTrueOrderByCodigo(UbicacionTipo tipo);

    List<Ubicacion> findByCodigoContainingIgnoreCaseAndActivoTrueOrderByCodigo(String q);

    List<Ubicacion> findByTipoAndCodigoContainingIgnoreCaseAndActivoTrueOrderByCodigo(UbicacionTipo tipo, String q);

    // NUEVOS: orden numérico (más natural) para tipo MUEBLE/CAJA
    List<Ubicacion> findByTipoAndActivoTrueOrderByMuebleNumAscEstanteriaNumAsc(UbicacionTipo tipo);

    List<Ubicacion> findByActivoTrueOrderByMuebleNumAscEstanteriaNumAsc();
}
