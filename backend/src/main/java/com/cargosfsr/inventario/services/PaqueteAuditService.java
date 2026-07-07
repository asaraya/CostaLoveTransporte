package com.cargosfsr.inventario.services;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.cargosfsr.inventario.auth.CurrentUser;
import com.cargosfsr.inventario.model.Paquete;

@Service
public class PaqueteAuditService {

    private final JdbcTemplate jdbc;
    private final CurrentUser currentUser;

    public PaqueteAuditService(JdbcTemplate jdbc, CurrentUser currentUser) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
    }

    private String actor(String user) {
        if (StringUtils.hasText(user)) return user.trim();
        try {
            String current = currentUser.display();
            if (StringUtils.hasText(current)) return current;
        } catch (Exception ignored) {}
        return "Sistema";
    }

    private static String clean(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    public String moduloDesdeMotivo(String motivo) {
        String m = motivo == null ? "" : motivo.toUpperCase(Locale.ROOT);
        if (m.contains("IMPORTACION") && m.contains("CONSOLIDADO")) return "IMPORTACION_CONSOLIDADO";
        if (m.contains("IMPORTACION") && (m.contains("TRACK") || m.contains("CSV"))) return "IMPORTACION_TRACKS_CSV";
        if (m.contains("STATUS EXTERNO") || m.contains("AUTO")) return "STATUS_EXTERNO";
        if (m.contains("AVISOS")) return "AVISOS";
        if (m.contains("ENTREGA")) return "CAMBIO_STATUS";
        if (m.contains("RECEPC")) return "RECEPCION";
        return "CAMBIO_STATUS";
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void registrar(Long paqueteId,
                          String trackingCode,
                          String accion,
                          String moduloOrigen,
                          String descripcion,
                          String campoAfectado,
                          Object valorAnterior,
                          Object valorNuevo,
                          String usuario,
                          String detalleJson) {
        if (!StringUtils.hasText(trackingCode)) return;
        jdbc.update("""
            INSERT INTO paquete_audit_log
                (paquete_id, tracking_code, accion, modulo_origen, descripcion, campo_afectado,
                 valor_anterior, valor_nuevo, usuario, detalle_json)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            paqueteId,
            trackingCode.trim().toUpperCase(Locale.ROOT),
            clean(accion),
            clean(moduloOrigen),
            clean(descripcion),
            clean(campoAfectado),
            str(valorAnterior),
            str(valorNuevo),
            actor(usuario),
            StringUtils.hasText(detalleJson) ? detalleJson : null
        );
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void registrarPorTracking(String trackingCode,
                                     String accion,
                                     String moduloOrigen,
                                     String descripcion,
                                     String campoAfectado,
                                     Object valorAnterior,
                                     Object valorNuevo,
                                     String usuario,
                                     String detalleJson) {
        if (!StringUtils.hasText(trackingCode)) return;
        String t = trackingCode.trim().toUpperCase(Locale.ROOT);
        Long paqueteId = null;
        try {
            paqueteId = jdbc.query("SELECT id FROM paquetes WHERE tracking_code = ? LIMIT 1", rs -> rs.next() ? rs.getLong("id") : null, t);
        } catch (Exception ignored) {}
        registrar(paqueteId, t, accion, moduloOrigen, descripcion, campoAfectado, valorAnterior, valorNuevo, usuario, detalleJson);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void registrarCreacion(Paquete paquete, String moduloOrigen, String usuario) {
        if (paquete == null) return;
        String tracking = paquete.getTrackingCode();
        registrar(
            paquete.getId(),
            tracking,
            "CREACION_PAQUETE",
            moduloOrigen,
            "Se agregó el paquete " + tracking + " desde " + moduloOrigen + ".",
            "estado",
            null,
            paquete.getEstado() != null ? paquete.getEstado().name() : null,
            usuario,
            null
        );
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void registrarCambioEstado(Paquete paquete, Object anterior, Object nuevo, String moduloOrigen, String motivo, String usuario) {
        if (paquete == null) return;
        String tracking = paquete.getTrackingCode();
        String oldVal = str(anterior);
        String newVal = str(nuevo);
        String desc = "Se cambió el estado del paquete " + tracking + " de " + oldVal + " a " + newVal + " desde " + moduloOrigen + ".";
        if (StringUtils.hasText(motivo)) desc += " Motivo: " + motivo.trim() + ".";
        registrar(paquete.getId(), tracking, "CAMBIO_ESTADO", moduloOrigen, desc, "estado", oldVal, newVal, usuario, null);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void registrarEliminacion(Paquete paquete, String moduloOrigen, String usuario) {
        if (paquete == null) return;
        String tracking = paquete.getTrackingCode();
        registrar(
            paquete.getId(),
            tracking,
            "ELIMINACION_PAQUETE",
            moduloOrigen,
            "Se eliminó el paquete " + tracking + " desde " + moduloOrigen + ".",
            "paquete",
            "EXISTENTE",
            "ELIMINADO",
            usuario,
            null
        );
    }

    @Transactional(readOnly = true)
    public Map<String, Object> historialPorTracking(String trackingCode) {
        if (!StringUtils.hasText(trackingCode)) {
            throw new IllegalArgumentException("Tracking requerido");
        }
        String t = trackingCode.trim().toUpperCase(Locale.ROOT);
        List<Map<String, Object>> items = jdbc.queryForList("""
            SELECT id, paquete_id, tracking_code, accion, modulo_origen, descripcion,
                   campo_afectado, valor_anterior, valor_nuevo, usuario, fecha_hora,
                   batch_id, estado_historial_id, detalle_json
              FROM paquete_audit_log
             WHERE tracking_code = ?
             ORDER BY fecha_hora DESC, id DESC
            """, t);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tracking", t);
        out.put("total", items.size());
        out.put("items", items);
        return out;
    }
}
