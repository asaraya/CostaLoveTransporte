package com.cargosfsr.inventario.model;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "sacos", uniqueConstraints = @UniqueConstraint(name = "uk_sacos_marchamo", columnNames = "marchamo"))
public class Saco {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String marchamo;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt = Instant.now();

    @Column(name = "closed_at")
    private Instant closedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "default_ubicacion_id")
    private Ubicacion defaultUbicacion;

    public Long getId() { return id; }

    public String getMarchamo() { return marchamo; }
    public void setMarchamo(String marchamo) { this.marchamo = marchamo; }

    public Instant getReceivedAt() { return receivedAt; }
    public void setReceivedAt(Instant receivedAt) { this.receivedAt = receivedAt; }

    public Instant getClosedAt() { return closedAt; }
    public void setClosedAt(Instant closedAt) { this.closedAt = closedAt; }


    public Ubicacion getDefaultUbicacion() { return defaultUbicacion; }
    public void setDefaultUbicacion(Ubicacion defaultUbicacion) { this.defaultUbicacion = defaultUbicacion; }
}
