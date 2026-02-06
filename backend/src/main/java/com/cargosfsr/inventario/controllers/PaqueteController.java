package com.cargosfsr.inventario.controllers;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.cargosfsr.inventario.model.enums.DevolucionSubtipo;
import com.cargosfsr.inventario.model.enums.PaqueteEstado;
import com.cargosfsr.inventario.repository.PaqueteRepository;
import com.cargosfsr.inventario.repository.PaqueteRepository.DevolucionRow;
import com.cargosfsr.inventario.services.EstadoService;
import com.cargosfsr.inventario.services.RegistroService;

@RestController
@RequestMapping("/api/paquetes")
public class PaqueteController {

    private final EstadoService estadoService;
    private final RegistroService registroService;
    private final PaqueteRepository paquetes;

    public PaqueteController(EstadoService estadoService,
                             RegistroService registroService,
                             PaqueteRepository paquetes) {
        this.estadoService = estadoService;
        this.registroService = registroService;
        this.paquetes = paquetes;
    }

    // ===== Alta rápida de paquete (Recepción) =====
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> preregistrar(@RequestBody PreregistroReq body) {

        String tracking = (body.getTracking() != null && !body.getTracking().isBlank())
                ? body.getTracking()
                : body.getTrackingCode(); // compat

        // distrito (nuevo). Si te quedara algún FE viejo mandando "ubicacionCodigo", lo aceptamos como alias.
        String distrito = (body.getDistritoNombre() != null && !body.getDistritoNombre().isBlank())
                ? body.getDistritoNombre()
                : body.getUbicacionCodigo(); // alias

        return registroService.preregistrar(
            tracking,
            body.getMarchamo(),
            distrito,
            body.getReceivedAt()
        );
    }

    @GetMapping("/{tracking}/exists")
    public Map<String, Boolean> exists(@PathVariable String tracking) {
        boolean ok = paquetes.existsByTrackingCode(tracking.trim().toUpperCase());
        return Map.of("exists", ok);
    }

    @DeleteMapping("/{tracking}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void eliminarPaquete(@PathVariable String tracking) {
        registroService.eliminarPaquetePorTracking(tracking);
    }

    @PutMapping("/{tracking}/estado")
    public Map<String, Object> cambiarEstado(
            @PathVariable String tracking,
            @RequestBody CambiarEstadoReq body) {
        PaqueteEstado nuevo = PaqueteEstado.valueOf(body.getNuevoEstado());
        return estadoService.actualizarEstadoPorTracking(
                tracking,
                nuevo,
                body.getMotivo(),
                body.getChangedBy(),
                body.isForce(),
                body.getWhen(),
                body.getDevolucionSubtipo()
        );
    }

    @PostMapping("/estado/bulk")
    public Map<String, Object> cambiarEstadoBulk(@RequestBody CambiarEstadoBulkReq body) {
        PaqueteEstado nuevo = PaqueteEstado.valueOf(body.getNuevoEstado());
        return estadoService.actualizarEstadoBulk(
                body.getTrackings(),
                nuevo,
                body.getMotivo(),
                body.getChangedBy(),
                body.isForce(),
                body.getWhen(),
                body.getDevolucionSubtipo()
        );
    }

    // ===== Listado NO ENTREGABLE (devolución) =====
    @GetMapping("/devolucion")
    public List<Map<String, Object>> listarDevolucion(
            @RequestParam(required = false) String subtipo,               // FUERA_DE_RUTA | VENCIDOS | DOS_INTENTOS | ALL/null
            @RequestParam(required = false) String marchamo,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant hasta
    ) {
        DevolucionSubtipo sub = null;
        if (subtipo != null && !subtipo.isBlank() && !"ALL".equalsIgnoreCase(subtipo)) {
            sub = DevolucionSubtipo.valueOf(subtipo.trim().toUpperCase());
        }

        List<DevolucionRow> rows = paquetes.buscarDevoluciones(
                PaqueteEstado.NO_ENTREGABLE, sub, desde, hasta, marchamo);

        return rows.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("trackingCode", r.getTrackingCode());
            m.put("estado", r.getEstado() != null ? r.getEstado().name() : null);
            m.put("devolucionSubtipo", r.getDevolucionSubtipo() != null ? r.getDevolucionSubtipo().name() : null);
            m.put("receivedAt", r.getReceivedAt());
            m.put("deliveredAt", r.getDeliveredAt());
            m.put("returnedAt", r.getReturnedAt());
            m.put("lastStateChangeAt", r.getLastStateChangeAt());
            m.put("recipientName", r.getRecipientName());
            m.put("recipientPhone", r.getRecipientPhone());
            m.put("recipientAddress", r.getRecipientAddress());
            m.put("marchamo", r.getMarchamo());

            // nuevo
            m.put("distritoNombre", r.getDistritoNombre());

            // alias por compat (si un FE viejo lo consume)
            m.put("ubicacionCodigo", r.getDistritoNombre());
            return m;
        }).collect(Collectors.toList());
    }

    // ===== DTOs =====

    public static class PreregistroReq {
        private String trackingCode; // compat
        private String tracking;     // alternativo
        private String marchamo;

        private String distritoNombre; // NUEVO
        private String ubicacionCodigo; // alias compat

        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        private Instant receivedAt;

        public String getTrackingCode() { return trackingCode; }
        public void setTrackingCode(String trackingCode) { this.trackingCode = trackingCode; }

        public String getTracking() { return tracking; }
        public void setTracking(String tracking) { this.tracking = tracking; }

        public String getMarchamo() { return marchamo; }
        public void setMarchamo(String marchamo) { this.marchamo = marchamo; }

        public String getDistritoNombre() { return distritoNombre; }
        public void setDistritoNombre(String distritoNombre) { this.distritoNombre = distritoNombre; }

        public String getUbicacionCodigo() { return ubicacionCodigo; }
        public void setUbicacionCodigo(String ubicacionCodigo) { this.ubicacionCodigo = ubicacionCodigo; }

        public Instant getReceivedAt() { return receivedAt; }
        public void setReceivedAt(Instant receivedAt) { this.receivedAt = receivedAt; }
    }

    public static class CambiarEstadoReq {
        private String nuevoEstado;
        private String motivo;
        private String changedBy;
        private boolean force;

        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        private Instant when;

        private String devolucionSubtipo; // FUERA_DE_RUTA | VENCIDOS | DOS_INTENTOS

        public String getNuevoEstado() { return nuevoEstado; }
        public void setNuevoEstado(String nuevoEstado) { this.nuevoEstado = nuevoEstado; }

        public String getMotivo() { return motivo; }
        public void setMotivo(String motivo) { this.motivo = motivo; }

        public String getChangedBy() { return changedBy; }
        public void setChangedBy(String changedBy) { this.changedBy = changedBy; }

        public boolean isForce() { return force; }
        public void setForce(boolean force) { this.force = force; }

        public Instant getWhen() { return when; }
        public void setWhen(Instant when) { this.when = when; }

        public String getDevolucionSubtipo() { return devolucionSubtipo; }
        public void setDevolucionSubtipo(String devolucionSubtipo) { this.devolucionSubtipo = devolucionSubtipo; }
    }

    public static class CambiarEstadoBulkReq {
        private List<String> trackings;
        private String nuevoEstado;
        private String motivo;
        private String changedBy;
        private boolean force;

        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        private Instant when;

        private String devolucionSubtipo;

        public List<String> getTrackings() { return trackings; }
        public void setTrackings(List<String> trackings) { this.trackings = trackings; }

        public String getNuevoEstado() { return nuevoEstado; }
        public void setNuevoEstado(String nuevoEstado) { this.nuevoEstado = nuevoEstado; }

        public String getMotivo() { return motivo; }
        public void setMotivo(String motivo) { this.motivo = motivo; }

        public String getChangedBy() { return changedBy; }
        public void setChangedBy(String changedBy) { this.changedBy = changedBy; }

        public boolean isForce() { return force; }
        public void setForce(boolean force) { this.force = force; }

        public Instant getWhen() { return when; }
        public void setWhen(Instant when) { this.when = when; }

        public String getDevolucionSubtipo() { return devolucionSubtipo; }
        public void setDevolucionSubtipo(String devolucionSubtipo) { this.devolucionSubtipo = devolucionSubtipo; }
    }

    public static class BulkDeleteReq {
        private java.util.List<String> trackings;
        public java.util.List<String> getTrackings() { return trackings; }
        public void setTrackings(java.util.List<String> trackings) { this.trackings = trackings; }
    }

    @PostMapping("/bulk-delete")
    public java.util.Map<String, Object> eliminarPaquetesMasivo(@RequestBody BulkDeleteReq body) {
        java.util.List<String> list = (body != null) ? body.getTrackings() : java.util.Collections.emptyList();
        return registroService.eliminarPaquetesEnLote(list);
    }
}
