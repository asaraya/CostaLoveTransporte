package com.cargosfsr.inventario.model;

import java.time.Instant;

import com.cargosfsr.inventario.model.enums.PaqueteEstado;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "paquete_estado_historial",
       indexes = @Index(name = "idx_historial_changed_at", columnList = "changed_at"))
public class PaqueteEstadoHistorial {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "paquete_id", nullable = false)
    private Paquete paquete;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado_from")
    private PaqueteEstado estadoFrom;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado_to", nullable = false)
    private PaqueteEstado estadoTo;

    @Column(name = "changed_at", nullable = false)
    private Instant changedAt = Instant.now();

    private String motivo;
    private String changedBy;

    // --- getters/setters ---
    public Long getId() { return id; }
    public Paquete getPaquete() { return paquete; }
    public void setPaquete(Paquete paquete) { this.paquete = paquete; }
    public PaqueteEstado getEstadoFrom() { return estadoFrom; }
    public void setEstadoFrom(PaqueteEstado estadoFrom) { this.estadoFrom = estadoFrom; }
    public PaqueteEstado getEstadoTo() { return estadoTo; }
    public void setEstadoTo(PaqueteEstado estadoTo) { this.estadoTo = estadoTo; }
    public Instant getChangedAt() { return changedAt; }
    public void setChangedAt(Instant changedAt) { this.changedAt = changedAt; }
    public String getMotivo() { return motivo; }
    public void setMotivo(String motivo) { this.motivo = motivo; }
    public String getChangedBy() { return changedBy; }
    public void setChangedBy(String changedBy) { this.changedBy = changedBy; }
}
