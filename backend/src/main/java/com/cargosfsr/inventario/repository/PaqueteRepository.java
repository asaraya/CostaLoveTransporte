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

    boolean existsByTrackingCode(String trackingCode);

    @EntityGraph(attributePaths = {"saco", "distrito"})
    Optional<Paquete> findWithSacoAndDistritoById(Long id);

    /** Elimina por tracking code y retorna cuántas filas fueron borradas. */
    @Modifying
    @Transactional
    long deleteByTrackingCode(String trackingCode);

    // ===== consultas para eliminación masiva =====
    List<Paquete> findByTrackingCodeIn(java.util.Collection<String> trackings);

    @Modifying
    @Transactional
    @Query("DELETE FROM Paquete p WHERE p.trackingCode IN :trackings")
    int deleteByTrackingCodeIn(@Param("trackings") java.util.Collection<String> trackings);

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
    }

    @Query("""
        select p.id as id,
               p.trackingCode as trackingCode,
               p.estado as estado,
               p.devolucionSubtipo as devolucionSubtipo,
               p.receivedAt as receivedAt,
               p.deliveredAt as deliveredAt,
               p.returnedAt as returnedAt,
               p.lastStateChangeAt as lastStateChangeAt,
               p.recipientName as recipientName,
               p.recipientPhone as recipientPhone,
               p.recipientAddress as recipientAddress,
               s.marchamo as marchamo,
               d.nombre as distritoNombre
        from Paquete p
          join p.saco s
          join p.distrito d
        where p.estado = :estado
          and (:subtipo is null or p.devolucionSubtipo = :subtipo)
          and (:desde is null or p.returnedAt >= :desde)
          and (:hasta is null or p.returnedAt < :hasta)
          and (:marchamo is null or s.marchamo = :marchamo)
        order by p.returnedAt desc, p.id desc
    """)
    List<DevolucionRow> buscarDevoluciones(@Param("estado") PaqueteEstado estado,
                                           @Param("subtipo") DevolucionSubtipo subtipo,
                                           @Param("desde") Instant desde,
                                           @Param("hasta") Instant hasta,
                                           @Param("marchamo") String marchamo);
}
