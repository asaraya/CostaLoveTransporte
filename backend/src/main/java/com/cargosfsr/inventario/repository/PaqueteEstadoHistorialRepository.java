package com.cargosfsr.inventario.repository;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import com.cargosfsr.inventario.model.PaqueteEstadoHistorial;

public interface PaqueteEstadoHistorialRepository extends CrudRepository<PaqueteEstadoHistorial, Long> {

    @Modifying
    @Query(value = "CALL sp_th_historial_eliminar_paquete(:paqueteId)", nativeQuery = true)
    void deleteByPaqueteId(@Param("paqueteId") Long paqueteId);

    @Modifying
    @Query(value = "CALL sp_th_historial_eliminar_lote(:paqueteIds)", nativeQuery = true)
    int deleteByPaqueteIdIn(@Param("paqueteIds") String paqueteIds);
}
