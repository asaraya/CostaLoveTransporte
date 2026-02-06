package com.cargosfsr.inventario.model;

import java.math.BigDecimal;
import java.time.Instant;

import com.cargosfsr.inventario.model.enums.DevolucionSubtipo;
import com.cargosfsr.inventario.model.enums.PaqueteEstado;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(
    name = "paquetes",
    uniqueConstraints = @UniqueConstraint(name = "uk_paquetes_tracking", columnNames = "tracking_code")
)
public class Paquete {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tracking_code", nullable = false, length = 100)
    private String trackingCode;

    @Column(nullable = true, length = 150)
    private String recipientName;

    @Column(nullable = true, length = 255)
    private String recipientAddress;

    @Column(name = "recipient_phone", length = 50)
    private String recipientPhone;

    @Column(name = "merchandise_value", precision = 12, scale = 2)
    private BigDecimal merchandiseValue;

    @Column(name = "content_description", length = 500)
    private String contentDescription;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "saco_id", nullable = false)
    private Saco saco;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "distrito_id", nullable = false)
    private Distrito distrito;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 80)
    private PaqueteEstado estado = PaqueteEstado.ENTREGADO_A_TRANSPORTISTA_LOCAL;

    @Enumerated(EnumType.STRING)
    @Column(name = "devolucion_subtipo", nullable = false)
    private DevolucionSubtipo devolucionSubtipo = DevolucionSubtipo.FUERA_DE_RUTA;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt = Instant.now();

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "returned_at")
    private Instant returnedAt;

    @Column(name = "last_state_change_at", nullable = false)
    private Instant lastStateChangeAt = Instant.now();

    @Column(name = "cambio_en_sistema_por", length = 100)
    private String cambioEnSistemaPor;

    @Column(name = "responsable_consolidado", length = 100)
    private String responsableConsolidado;

    @Column(name = "observaciones", length = 500)
    private String observaciones;

    @Column(name = "status_externo", length = 100)
    private String statusExterno;

    @Column(name = "status_externo_at")
    private Instant statusExternoAt;

    @Lob
    @Column(name = "notes")
    private String notes;

    // --- getters/setters ---
    public Long getId() { return id; }

    public String getTrackingCode() { return trackingCode; }
    public void setTrackingCode(String trackingCode) { this.trackingCode = trackingCode; }

    public String getRecipientName() { return recipientName; }
    public void setRecipientName(String recipientName) { this.recipientName = recipientName; }

    public String getRecipientAddress() { return recipientAddress; }
    public void setRecipientAddress(String recipientAddress) { this.recipientAddress = recipientAddress; }

    public String getRecipientPhone() { return recipientPhone; }
    public void setRecipientPhone(String recipientPhone) { this.recipientPhone = recipientPhone; }

    public BigDecimal getMerchandiseValue() { return merchandiseValue; }
    public void setMerchandiseValue(BigDecimal merchandiseValue) { this.merchandiseValue = merchandiseValue; }

    public String getContentDescription() { return contentDescription; }
    public void setContentDescription(String contentDescription) { this.contentDescription = contentDescription; }

    public Saco getSaco() { return saco; }
    public void setSaco(Saco saco) { this.saco = saco; }

    public Distrito getDistrito() { return distrito; }
    public void setDistrito(Distrito distrito) { this.distrito = distrito; }

    public PaqueteEstado getEstado() { return estado; }
    public void setEstado(PaqueteEstado estado) { this.estado = estado; }

    public DevolucionSubtipo getDevolucionSubtipo() { return devolucionSubtipo; }
    public void setDevolucionSubtipo(DevolucionSubtipo devolucionSubtipo) { this.devolucionSubtipo = devolucionSubtipo; }

    public Instant getReceivedAt() { return receivedAt; }
    public void setReceivedAt(Instant receivedAt) { this.receivedAt = receivedAt; }

    public Instant getDeliveredAt() { return deliveredAt; }
    public void setDeliveredAt(Instant deliveredAt) { this.deliveredAt = deliveredAt; }

    public Instant getReturnedAt() { return returnedAt; }
    public void setReturnedAt(Instant returnedAt) { this.returnedAt = returnedAt; }

    public Instant getLastStateChangeAt() { return lastStateChangeAt; }
    public void setLastStateChangeAt(Instant lastStateChangeAt) { this.lastStateChangeAt = lastStateChangeAt; }

    public String getCambioEnSistemaPor() { return cambioEnSistemaPor; }
    public void setCambioEnSistemaPor(String cambioEnSistemaPor) { this.cambioEnSistemaPor = cambioEnSistemaPor; }

    public String getResponsableConsolidado() { return responsableConsolidado; }
    public void setResponsableConsolidado(String responsableConsolidado) { this.responsableConsolidado = responsableConsolidado; }

    public String getObservaciones() { return observaciones; }
    public void setObservaciones(String observaciones) { this.observaciones = observaciones; }

    public String getStatusExterno() { return statusExterno; }
    public void setStatusExterno(String statusExterno) { this.statusExterno = statusExterno; }

    public Instant getStatusExternoAt() { return statusExternoAt; }
    public void setStatusExternoAt(Instant statusExternoAt) { this.statusExternoAt = statusExternoAt; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
}
