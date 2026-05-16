package com.cargosfsr.inventario.model;

import java.time.Instant;

import com.cargosfsr.inventario.model.enums.UbicacionTipo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "ubicacion",
       uniqueConstraints = @UniqueConstraint(name = "uk_ubicacion_codigo", columnNames = "codigo"))
public class Ubicacion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UbicacionTipo tipo;

    @Column(name = "mueble_num")
    private Integer muebleNum;

    @Column(name = "estanteria_num")
    private Integer estanteriaNum;

    @Column(name = "caja_codigo")
    private String cajaCodigo;

    @Column(nullable = false, length = 100)
    private String codigo; // Ej: M3-E2 o CAJA-01

    @Column(nullable = false)
    private boolean activo = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    // --- getters/setters ---
    public Long getId() { return id; }
    public UbicacionTipo getTipo() { return tipo; }
    public void setTipo(UbicacionTipo tipo) { this.tipo = tipo; }
    public Integer getMuebleNum() { return muebleNum; }
    public void setMuebleNum(Integer muebleNum) { this.muebleNum = muebleNum; }
    public Integer getEstanteriaNum() { return estanteriaNum; }
    public void setEstanteriaNum(Integer estanteriaNum) { this.estanteriaNum = estanteriaNum; }
    public String getCajaCodigo() { return cajaCodigo; }
    public void setCajaCodigo(String cajaCodigo) { this.cajaCodigo = cajaCodigo; }
    public String getCodigo() { return codigo; }
    public void setCodigo(String codigo) { this.codigo = codigo; }
    public boolean isActivo() { return activo; }
    public void setActivo(boolean activo) { this.activo = activo; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
