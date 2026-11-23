package com.cargosfsr.inventario.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.cargosfsr.inventario.model.Paquete;
import com.cargosfsr.inventario.model.enums.DevolucionSubtipo;
import com.cargosfsr.inventario.model.enums.PaqueteEstado;

public interface PaqueteRepository extends JpaRepository<Paquete, Long> {

    Optional<Paquete> findByTrackingCode(String trackingCode);

    /** Serializa cambios concurrentes del mismo paquete dentro de la transacción. */
    @Query(value = "CALL sp_th_bloquear_tracking(:tracking)", nativeQuery = true)
    Optional<Paquete> findByTrackingCodeForUpdate(@Param("tracking") String tracking);

    boolean existsByTrackingCode(String trackingCode);

    @EntityGraph(attributePaths = {"saco", "distrito", "ubicacion"})
    Optional<Paquete> findWithSacoDistritoAndUbicacionById(Long id);

    @EntityGraph(attributePaths = {"saco", "distrito"})
    Optional<Paquete> findWithSacoAndDistritoById(Long id);

    /** Elimina por tracking code y retorna cuántas filas fueron borradas. */
    @Modifying
    @Transactional
    long deleteByTrackingCode(String trackingCode);

    // ===== consultas para eliminación masiva =====
    List<Paquete> findByTrackingCodeIn(java.util.Collection<String> trackings);

    /** Cuenta paquetes por marchamo del saco asociado. */
    long countBySaco_Marchamo(String marchamo);

    /** Validar si un saco está vacío antes de borrarlo */
    long countBySacoId(Long sacoId);

    // Proyección para listar sin lazy/ciclos
    interface DevolucionRow {
        Long getId();
        String getTrackingCode();
        PaqueteEstado getEstado();
        DevolucionSubtipo getDevolucionSubtipo();
        Instant getReceivedAt();
        Instant getDeliveredAt();
        Instant getReturnedAt();
        Instant getLastStateChangeAt();
        String getRecipientName();
        String getRecipientPhone();
        String getRecipientAddress();
        String getMarchamo();
        String getDistritoNombre();
        String getUbicacionCodigo();
    }

    @Query(value = "CALL sp_th_devoluciones_listar(:estado, :subtipo, :desde, :hasta, :marchamo)", nativeQuery = true)
    List<DevolucionRow> buscarDevoluciones(@Param("estado") String estado,
                                           @Param("subtipo") String subtipo,
                                           @Param("desde") Instant desde,
                                           @Param("hasta") Instant hasta,
                                           @Param("marchamo") String marchamo);
}
