package com.cargosfsr.inventario.repository;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import com.cargosfsr.inventario.model.PaqueteEstadoHistorial;

public interface PaqueteEstadoHistorialRepository extends CrudRepository<PaqueteEstadoHistorial, Long> {

    @Modifying
    @Query("DELETE FROM PaqueteEstadoHistorial h WHERE h.paquete.id = :paqueteId")
    void deleteByPaqueteId(@Param("paqueteId") Long paqueteId);

    @Modifying
    @Query("DELETE FROM PaqueteEstadoHistorial h WHERE h.paquete.id IN :paqueteIds")
    int deleteByPaqueteIdIn(@Param("paqueteIds") java.util.Collection<Long> paqueteIds);
}
