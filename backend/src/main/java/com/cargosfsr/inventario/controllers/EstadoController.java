package com.cargosfsr.inventario.controllers;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cargosfsr.inventario.model.enums.PaqueteEstado;
import com.cargosfsr.inventario.services.EstadoService;

@RestController
@RequestMapping(path = "/api/estado", produces = MediaType.APPLICATION_JSON_VALUE)
public class EstadoController {

    private final EstadoService estadoService;
    public EstadoController(EstadoService estadoService) { this.estadoService = estadoService; }

    // ===== DTOs =====
    public static class CambioEstadoReq {
        public String tracking; public String estado; public String motivo;
        public Boolean force; public Instant when;
        public String devolucionSubtipo; // ENRUTE | OTRAS_ZONAS | VENCIDOS | NO_ENTREGAR
    }
    public static class CambioEstadoTextoReq {
        public String texto; public String estado; public String motivo;
        public Boolean force; public Instant when;
        public String devolucionSubtipo;
    }
    public static class CambioEstadoBulkReq {
        public List<String> trackings; public String estado; public String motivo;
        public Boolean force; public Instant when;
        public String devolucionSubtipo;
    }

    // ====== STATUS EXTERNO ======
    public static class StatusExternoReq {
        public String tracking;     // requerido (o usa /texto o /bulk)
        public String status;       // ej: "prueba de entrega", "push", "almacenaje", "en transito a bodegas Aeropost"
        public Instant statusAt;    // opcional
        public String changedBy;    // opcional
    }
    public static class StatusExternoTextoReq {
        public String texto; public String status; public Instant statusAt; public String changedBy;
    }
    public static class StatusExternoBulkReq {
        public List<String> trackings; public String status; public Instant statusAt; public String changedBy;
    }

    private PaqueteEstado parseEstado(String s) {
        if (s == null) throw new IllegalArgumentException("estado requerido");
        try { return PaqueteEstado.valueOf(s.trim().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException ex) { throw new IllegalArgumentException("estado inválido: " + s); }
    }

    @PostMapping(path = "/tracking", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> cambiarUno(@RequestBody CambioEstadoReq body) {
        if (body == null || body.tracking == null || body.tracking.isBlank())
            throw new IllegalArgumentException("tracking requerido");
        PaqueteEstado nuevo = parseEstado(body.estado);
        boolean force = body.force != null && body.force;
        return estadoService.actualizarEstadoPorTracking(
                body.tracking, nuevo, body.motivo, null, force, body.when, body.devolucionSubtipo
        );
    }

    @PostMapping(path = "/texto", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> cambiarDesdeTexto(@RequestBody CambioEstadoTextoReq body) {
        if (body == null || body.texto == null || body.texto.isBlank())
            throw new IllegalArgumentException("texto requerido");
        PaqueteEstado nuevo = parseEstado(body.estado);
        boolean force = body.force != null && body.force;
        return estadoService.actualizarEstadoDesdeTexto(
                body.texto, nuevo, body.motivo, null, force, body.when, body.devolucionSubtipo
        );
    }

    @PostMapping(path = "/bulk", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> cambiarBulk(@RequestBody CambioEstadoBulkReq body) {
        if (body == null || body.trackings == null || body.trackings.isEmpty())
            throw new IllegalArgumentException("lista de trackings vacía");
        PaqueteEstado nuevo = parseEstado(body.estado);
        boolean force = body.force != null && body.force;
        return estadoService.actualizarEstadoBulk(
                body.trackings, nuevo, body.motivo, null, force, body.when, body.devolucionSubtipo
        );
    }

    // ===== NUEVO: STATUS EXTERNO =====
    @PostMapping(path = "/status-externo/tracking", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> statusExternoUno(@RequestBody StatusExternoReq body) {
        if (body == null || body.tracking == null || body.tracking.isBlank())
            throw new IllegalArgumentException("tracking requerido");
        if (body.status == null || body.status.isBlank())
            throw new IllegalArgumentException("status requerido");
        return estadoService.aplicarStatusExterno(body.tracking, body.status, body.statusAt, body.changedBy);
    }

    @PostMapping(path = "/status-externo/texto", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> statusExternoTexto(@RequestBody StatusExternoTextoReq body) {
        if (body == null || body.texto == null || body.texto.isBlank())
            throw new IllegalArgumentException("texto requerido");
        if (body.status == null || body.status.isBlank())
            throw new IllegalArgumentException("status requerido");
        return estadoService.aplicarStatusExternoDesdeTexto(body.texto, body.status, body.statusAt, body.changedBy);
    }

    @PostMapping(path = "/status-externo/bulk", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> statusExternoBulk(@RequestBody StatusExternoBulkReq body) {
        if (body == null || body.trackings == null || body.trackings.isEmpty())
            throw new IllegalArgumentException("lista de trackings vacía");
        if (body.status == null || body.status.isBlank())
            throw new IllegalArgumentException("status requerido");
        return estadoService.aplicarStatusExternoBulk(body.trackings, body.status, body.statusAt, body.changedBy);
    }
}
