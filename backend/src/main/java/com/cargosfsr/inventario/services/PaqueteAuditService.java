package com.cargosfsr.inventario.services;

import java.util.ArrayList;
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

    private static String code(Object value) {
        String s = str(value);
        return StringUtils.hasText(s) ? s.trim().toUpperCase(Locale.ROOT) : "";
    }

    private static boolean isDevolucionCode(Object estado) {
        String e = code(estado);
        int idx = e.indexOf("__");
        if (idx >= 0) e = e.substring(0, idx);
        return "DEVOLUCION".equals(e) || "NO_ENTREGABLE".equals(e);
    }

    private static String estadoAuditValue(Object estado, Object subtipo) {
        String e = code(estado);
        if (!StringUtils.hasText(e)) return null;
        int idx = e.indexOf("__");
        if (idx >= 0) return e;
        String sub = code(subtipo);
        if (isDevolucionCode(e) && StringUtils.hasText(sub)) return e + "__" + sub;
        return e;
    }

    private static String labelSubtipo(String raw) {
        String s = code(raw);
        switch (s) {
            case "ENRUTE": return "En ruta";
            case "OTRAS_ZONAS": return "Otras zonas";
            case "VENCIDOS": return "Vencidos";
            case "NO_ENTREGAR": return "No entregar";
            case "TRANSPORTE": return "Transporte";
            case "FUERA_DE_RUTA": return "Fuera de ruta";
            case "DOS_INTENTOS": return "Dos intentos";
            default: return humanize(raw);
        }
    }

    private static String labelEstado(Object value) {
        String raw = code(value);
        if (!StringUtils.hasText(raw)) return "—";
        String estado = raw;
        String subtipo = null;
        int idx = raw.indexOf("__");
        if (idx >= 0) {
            estado = raw.substring(0, idx);
            subtipo = raw.substring(idx + 2);
        }
        switch (estado) {
            case "EN_INVENTARIO": return "En inventario";
            case "ENTREGADO": return "Entregado";
            case "DEVOLUCION":
            case "NO_ENTREGABLE":
                return StringUtils.hasText(subtipo) ? "Devolución (" + labelSubtipo(subtipo) + ")" : "Devolución";
            case "PUSH": return "Push";
            case "ALMACENAJE": return "Almacenaje";
            case "EN_TRANSITO_A_TIENDAS_AEROPOST": return "En tránsito a tiendas Aeropost";
            case "PRUEBA_DE_ENTREGA": return "Prueba de entrega";
            case "ENTREGADO_A_TRANSPORTISTA_LOCAL": return "Entregado a transportista local";
            case "NO_ENTREGADO_CONSIGNATARIO_DISPONIBLE": return "No entregado - consignatario no disponible";
            case "ENTREGADO_A_TRANSPORTISTA_LOCAL_2DO_INTENTO": return "Entregado a transportista local - 2do intento";
            case "TR_A_CA": return "TR a CA";
            default: return humanize(estado);
        }
    }

    private static String labelModulo(Object value) {
        String s = code(value);
        switch (s) {
            case "RECEPCION": return "Recepción";
            case "IMPORTACION_CONSOLIDADO": return "Importación de consolidado";
            case "IMPORTACION_TRACKS_CSV": return "Importación Tracks CSV";
            case "IMPORTACION": return "Importación";
            case "STATUS_EXTERNO": return "Status externo";
            case "CAMBIO_STATUS": return "Cambio de status";
            case "ENTREGAS": return "Entregas";
            case "AVISOS": return "Avisos";
            case "INVENTARIO": return "Inventario";
            case "MOVER_MUEBLES": return "Mover muebles";
            case "HISTORIAL_EXISTENTE": return "Historial existente";
            case "ADMIN": return "Administración";
            default: return humanize(value);
        }
    }

    private static String labelAccion(Object value) {
        String s = code(value);
        switch (s) {
            case "CREACION_PAQUETE": return "Creación de paquete";
            case "CAMBIO_ESTADO": return "Cambio de estado";
            case "CAMBIO_SUBTIPO_DEVOLUCION": return "Cambio de subtipo de devolución";
            case "CAMBIO_MARCHAMO": return "Cambio de marchamo";
            case "CAMBIO_UBICACION": return "Cambio de ubicación";
            case "CAMBIO_DISTRITO": return "Cambio de distrito";
            case "ACTUALIZACION_DATOS": return "Actualización de datos";
            case "ACTUALIZACION_STATUS_EXTERNO": return "Actualización de status externo";
            case "IMPORTACION_CONSOLIDADO": return "Importación de consolidado";
            case "IMPORTACION_TRACKS": return "Importación Tracks CSV";
            case "ELIMINACION_PAQUETE": return "Eliminación de paquete";
            case "CAMBIO_MASIVO": return "Cambio masivo";
            default: return humanize(value);
        }
    }

    private static String labelCampo(Object value) {
        String s = code(value);
        switch (s) {
            case "ESTADO": return "Estado";
            case "DEVOLUCION_SUBTIPO": return "Subtipo de devolución";
            case "PAQUETE": return "Paquete";
            case "STATUS_EXTERNO": return "Status externo";
            case "MARCHAMO": return "Marchamo";
            case "UBICACION": return "Ubicación";
            case "DISTRITO": return "Distrito";
            case "RECIPIENT_NAME": return "Nombre";
            case "RECIPIENT_ADDRESS": return "Dirección";
            case "RECIPIENT_PHONE": return "Teléfono";
            default: return humanize(value);
        }
    }

    private static String labelValor(Object campo, Object value) {
        if (value == null || !StringUtils.hasText(String.valueOf(value))) return "—";
        if ("ESTADO".equals(code(campo)) || "DEVOLUCION_SUBTIPO".equals(code(campo))) return labelEstado(value);
        String s = code(value);
        if (isDevolucionCode(s)) return labelEstado(s);
        if ("EXISTENTE".equals(s)) return "Existente";
        if ("ELIMINADO".equals(s)) return "Eliminado";
        return String.valueOf(value);
    }

    private static String humanize(Object value) {
        String s = str(value);
        if (!StringUtils.hasText(s)) return "—";
        String lower = s.trim().replace('_', ' ').replace('-', ' ').toLowerCase(Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
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
        String subtipoActual = null;
        try {
            Map<String, Object> info = jdbc.query("SELECT id, devolucion_subtipo FROM paquetes WHERE tracking_code = ? LIMIT 1", rs -> {
                if (!rs.next()) return null;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", rs.getLong("id"));
                m.put("devolucion_subtipo", rs.getString("devolucion_subtipo"));
                return m;
            }, t);
            if (info != null) {
                paqueteId = ((Number) info.get("id")).longValue();
                subtipoActual = str(info.get("devolucion_subtipo"));
            }
        } catch (Exception ignored) {}

        Object oldVal = valorAnterior;
        Object newVal = valorNuevo;
        String desc = descripcion;
        if ("ESTADO".equals(code(campoAfectado))) {
            oldVal = estadoAuditValue(valorAnterior, isDevolucionCode(valorAnterior) ? subtipoActual : null);
            newVal = estadoAuditValue(valorNuevo, isDevolucionCode(valorNuevo) ? subtipoActual : null);
            if ("CAMBIO_ESTADO".equals(code(accion))) {
                desc = descripcionCambioEstado(t, oldVal, newVal, moduloOrigen, null);
            }
        }
        registrar(paqueteId, t, accion, moduloOrigen, desc, campoAfectado, oldVal, newVal, usuario, detalleJson);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void registrarCreacion(Paquete paquete, String moduloOrigen, String usuario) {
        if (paquete == null) return;
        String tracking = paquete.getTrackingCode();
        String estado = estadoAuditValue(
            paquete.getEstado() != null ? paquete.getEstado().name() : null,
            paquete.getDevolucionSubtipo() != null ? paquete.getDevolucionSubtipo().name() : null
        );
        registrar(
            paquete.getId(),
            tracking,
            "CREACION_PAQUETE",
            moduloOrigen,
            "Se agregó el paquete " + tracking + " desde " + labelModulo(moduloOrigen) + " con estado " + labelEstado(estado) + ".",
            "estado",
            null,
            estado,
            usuario,
            null
        );
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void registrarCambioEstado(Paquete paquete, Object anterior, Object nuevo, String moduloOrigen, String motivo, String usuario) {
        String subtipo = paquete != null && paquete.getDevolucionSubtipo() != null ? paquete.getDevolucionSubtipo().name() : null;
        registrarCambioEstado(paquete, anterior, nuevo, null, subtipo, moduloOrigen, motivo, usuario);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void registrarCambioEstado(Paquete paquete,
                                      Object anterior,
                                      Object nuevo,
                                      Object subtipoAnterior,
                                      Object subtipoNuevo,
                                      String moduloOrigen,
                                      String motivo,
                                      String usuario) {
        if (paquete == null) return;
        String tracking = paquete.getTrackingCode();
        String oldVal = estadoAuditValue(anterior, subtipoAnterior);
        String newVal = estadoAuditValue(nuevo, subtipoNuevo);
        String desc = descripcionCambioEstado(tracking, oldVal, newVal, moduloOrigen, motivo);
        registrar(paquete.getId(), tracking, "CAMBIO_ESTADO", moduloOrigen, desc, "estado", oldVal, newVal, usuario, null);
    }

    private static String descripcionCambioEstado(String tracking, Object anterior, Object nuevo, String moduloOrigen, String motivo) {
        String desc = "Se cambió el estado del paquete " + tracking + " de " + labelEstado(anterior) + " a " + labelEstado(nuevo) + " desde " + labelModulo(moduloOrigen) + ".";
        if (StringUtils.hasText(motivo)) desc += " Motivo: " + motivo.trim() + ".";
        return desc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void registrarCambioSubtipoDevolucion(Paquete paquete,
                                                 Object estado,
                                                 Object subtipoAnterior,
                                                 Object subtipoNuevo,
                                                 String moduloOrigen,
                                                 String motivo,
                                                 String usuario) {
        if (paquete == null) return;
        String tracking = paquete.getTrackingCode();
        String estadoBase = estado != null
            ? code(estado)
            : paquete.getEstado() != null ? paquete.getEstado().name() : "DEVOLUCION";

        String oldVal = estadoAuditValue(estadoBase, subtipoAnterior);
        String newVal = estadoAuditValue(estadoBase, subtipoNuevo);

        String desc = "Se cambió el subtipo de devolución del paquete "
            + tracking
            + " de "
            + labelEstado(oldVal)
            + " a "
            + labelEstado(newVal)
            + " desde "
            + labelModulo(moduloOrigen)
            + ".";

        if (StringUtils.hasText(motivo)) {
            desc += " Motivo: " + motivo.trim() + ".";
        }

        registrar(
            paquete.getId(),
            tracking,
            "CAMBIO_SUBTIPO_DEVOLUCION",
            moduloOrigen,
            desc,
            "devolucion_subtipo",
            oldVal,
            newVal,
            usuario,
            null
        );
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
            "Se eliminó el paquete " + tracking + " desde " + labelModulo(moduloOrigen) + ".",
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
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT id, paquete_id, tracking_code, accion, modulo_origen, descripcion,
                   campo_afectado, valor_anterior, valor_nuevo, usuario, fecha_hora,
                   batch_id, estado_historial_id, detalle_json
              FROM paquete_audit_log
             WHERE tracking_code = ?
             ORDER BY fecha_hora DESC, id DESC
            """, t);

        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>(row);
            item.put("accion_label", labelAccion(row.get("accion")));
            item.put("modulo_origen_label", labelModulo(row.get("modulo_origen")));
            item.put("campo_label", labelCampo(row.get("campo_afectado")));
            item.put("valor_anterior_label", labelValor(row.get("campo_afectado"), row.get("valor_anterior")));
            item.put("valor_nuevo_label", labelValor(row.get("campo_afectado"), row.get("valor_nuevo")));
            item.put("descripcion_label", descripcionPresentacion(item));
            items.add(item);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tracking", t);
        out.put("total", items.size());
        out.put("items", items);
        return out;
    }

    private static String descripcionPresentacion(Map<String, Object> row) {
        String tracking = str(row.get("tracking_code"));
        String accion = code(row.get("accion"));
        String modulo = str(row.get("modulo_origen_label"));
        String campo = str(row.get("campo_label"));
        String antes = str(row.get("valor_anterior_label"));
        String despues = str(row.get("valor_nuevo_label"));

        if ("CREACION_PAQUETE".equals(accion)) {
            return "Se agregó el paquete " + tracking + " desde " + modulo + " con estado " + despues + ".";
        }
        if ("CAMBIO_ESTADO".equals(accion)) {
            return "Se cambió el estado del paquete " + tracking + " de " + antes + " a " + despues + " desde " + modulo + ".";
        }
        if ("CAMBIO_SUBTIPO_DEVOLUCION".equals(accion)) {
            return "Se cambió el subtipo de devolución del paquete " + tracking + " de " + antes + " a " + despues + " desde " + modulo + ".";
        }
        if ("ELIMINACION_PAQUETE".equals(accion)) {
            return "Se eliminó el paquete " + tracking + " desde " + modulo + ".";
        }
        if ("ACTUALIZACION_STATUS_EXTERNO".equals(accion)) {
            return "Se actualizó el status externo del paquete " + tracking + " desde " + modulo + ".";
        }
        if (StringUtils.hasText(campo) && !"—".equals(campo)) {
            return "Se actualizó " + campo.toLowerCase(Locale.ROOT) + " del paquete " + tracking + " desde " + modulo + ".";
        }
        String desc = str(row.get("descripcion"));
        return StringUtils.hasText(desc) ? desc : "Movimiento registrado.";
    }
}
