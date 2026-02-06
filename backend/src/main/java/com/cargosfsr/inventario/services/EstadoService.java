package com.cargosfsr.inventario.services;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.cargosfsr.inventario.auth.CurrentUser;
import com.cargosfsr.inventario.model.Paquete;
import com.cargosfsr.inventario.model.PaqueteEstadoHistorial;
import com.cargosfsr.inventario.model.enums.DevolucionSubtipo;
import com.cargosfsr.inventario.model.enums.PaqueteEstado;
import com.cargosfsr.inventario.repository.PaqueteEstadoHistorialRepository;
import com.cargosfsr.inventario.repository.PaqueteRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

@Service
public class EstadoService {

    private final CurrentUser currentUser;

    private static final Pattern TRACKING_PATTERN =
            Pattern.compile("(HZCR|CR)\\d+", Pattern.CASE_INSENSITIVE);

    private final PaqueteRepository paquetes;
    private final PaqueteEstadoHistorialRepository historial;

    @PersistenceContext
    private EntityManager em;

    public EstadoService(PaqueteRepository paquetes,
                         PaqueteEstadoHistorialRepository historial,
                         CurrentUser currentUser) {
        this.currentUser = currentUser;
        this.paquetes = paquetes;
        this.historial = historial;
    }

    private String actor(String changedByNullable) {
        if (changedByNullable != null && !changedByNullable.isBlank()) return changedByNullable;
        return currentUser.display();
    }

    /** Inicializa la sesión SQL con TZ CR y el usuario para triggers/SPs */
    private void initDbSession(String who) {
        em.createNativeQuery("SET time_zone = '-06:00'").executeUpdate();
        em.createNativeQuery("SET @changed_by := :who")
          .setParameter("who", who)
          .executeUpdate();
    }

    public List<String> extraerTrackingsDesdeTexto(String raw) {
        List<String> out = new ArrayList<>();
        if (!StringUtils.hasText(raw)) return out;
        Matcher m = TRACKING_PATTERN.matcher(raw);
        LinkedHashSet<String> uniq = new LinkedHashSet<>();
        while (m.find()) uniq.add(m.group().toUpperCase());
        out.addAll(uniq);
        return out;
    }

    // ================== ESTADO PRINCIPAL ==================
    @Transactional
    @CacheEvict(cacheNames = { "inventario", "busquedas" }, allEntries = true)
    public Map<String, Object> actualizarEstadoPorTracking(String tracking,
                                                           PaqueteEstado nuevo,
                                                           String motivo,
                                                           String changedByIgnored,
                                                           boolean force,
                                                           Instant when,
                                                           String devolucionSubtipoOpt) {
        if (!StringUtils.hasText(tracking)) throw new IllegalArgumentException("Tracking requerido");
        String t = tracking.trim().toUpperCase();
        if (!TRACKING_PATTERN.matcher(t).matches())
            throw new IllegalArgumentException("Formato de tracking inválido (HZCR/CR + dígitos)");

        Paquete p = paquetes.findByTrackingCode(t).orElseThrow(
            () -> new IllegalArgumentException("No existe paquete con tracking: " + t)
        );

        String user = actor(changedByIgnored);
        initDbSession(user);

        PaqueteEstado anterior = p.getEstado();
        Instant ts = (when != null ? when : Instant.now());

        boolean touchedDelivered = false;
        boolean touchedReturned  = false;

        DevolucionSubtipo sub = null;
        if (devolucionSubtipoOpt != null && !devolucionSubtipoOpt.isBlank()) {
            sub = DevolucionSubtipo.valueOf(devolucionSubtipoOpt.trim().toUpperCase());
        }

        // Reglas nuevas
        if (nuevo == PaqueteEstado.ENTREGADO_A_TRANSPORTISTA_LOCAL
                || nuevo == PaqueteEstado.ENTREGADO_A_TRANSPORTISTA_LOCAL_2DO_INTENTO) {
            p.setDeliveredAt(ts);
            touchedDelivered = true;
        } else if (nuevo == PaqueteEstado.NO_ENTREGABLE) {
            p.setReturnedAt(ts);
            touchedReturned = true;
            p.setDevolucionSubtipo(sub != null ? sub : DevolucionSubtipo.FUERA_DE_RUTA);
        } else if (nuevo == PaqueteEstado.NO_ENTREGADO_CONSIGNATARIO_DISPONIBLE && force) {
            // reset a "disponible": limpia entregas/devoluciones
            if (p.getDeliveredAt() != null || p.getReturnedAt() != null) {
                p.setDeliveredAt(null);
                p.setReturnedAt(null);
                // no marcamos touched* porque es nullear; se hace con save + update directo aparte
            }
        }

        boolean changesState = (anterior != nuevo) ||
                               (nuevo == PaqueteEstado.NO_ENTREGADO_CONSIGNATARIO_DISPONIBLE && force);

        if (!changesState) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("tracking", t);
            out.put("estado_anterior", anterior != null ? anterior.name() : null);
            out.put("estado_nuevo", nuevo.name());
            out.put("changed", false);
            out.put("when", ts);
            out.put("changed_by", user);
            out.put("delivered_at", p.getDeliveredAt());
            out.put("returned_at", p.getReturnedAt());
            out.put("devolucion_subtipo", p.getDevolucionSubtipo() != null ? p.getDevolucionSubtipo().name() : null);
            return out;
        }

        p.setEstado(nuevo);
        p.setLastStateChangeAt(ts);
        paquetes.save(p);

        // Ajuste -6h hecho por MySQL + último cambio por
        StringBuilder sql = new StringBuilder(
            "UPDATE paquetes SET " +
            "last_state_change_at = DATE_SUB(:ts, INTERVAL 6 HOUR), " +
            "cambio_en_sistema_por = :who"
        );

        // timestamps de estado
        if (touchedDelivered) sql.append(", delivered_at = DATE_SUB(:ts, INTERVAL 6 HOUR)");
        if (touchedReturned)  sql.append(", returned_at  = DATE_SUB(:ts, INTERVAL 6 HOUR)");

        // reset forzado (limpiar)
        if (nuevo == PaqueteEstado.NO_ENTREGADO_CONSIGNATARIO_DISPONIBLE && force) {
            sql.append(", delivered_at = NULL, returned_at = NULL");
        }

        sql.append(" WHERE id = :id");

        em.createNativeQuery(sql.toString())
          .setParameter("ts", Timestamp.from(ts))
          .setParameter("who", user)
          .setParameter("id", p.getId())
          .executeUpdate();

        PaqueteEstadoHistorial h = new PaqueteEstadoHistorial();
        h.setPaquete(p);
        h.setEstadoFrom(anterior);
        h.setEstadoTo(nuevo);
        h.setChangedAt(ts);
        h.setMotivo(motivo);
        h.setChangedBy(user);
        historial.save(h);

        em.createNativeQuery("""
            UPDATE paquete_estado_historial
               SET changed_at = DATE_SUB(changed_at, INTERVAL 6 HOUR)
             WHERE id = :id
        """).setParameter("id", h.getId())
          .executeUpdate();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tracking", t);
        out.put("estado_anterior", anterior != null ? anterior.name() : null);
        out.put("estado_nuevo", nuevo.name());
        out.put("changed", true);
        out.put("when", ts);
        out.put("changed_by", user);
        out.put("delivered_at", p.getDeliveredAt());
        out.put("returned_at", p.getReturnedAt());
        out.put("devolucion_subtipo", p.getDevolucionSubtipo() != null ? p.getDevolucionSubtipo().name() : null);
        return out;
    }

    @Transactional
    @CacheEvict(cacheNames = { "inventario", "busquedas" }, allEntries = true)
    public Map<String, Object> actualizarEstadoDesdeTexto(String rawTrackings,
                                                          PaqueteEstado nuevo,
                                                          String motivo,
                                                          String changedByIgnored,
                                                          boolean force,
                                                          Instant when,
                                                          String devolucionSubtipoOpt) {
        List<String> trackings = extraerTrackingsDesdeTexto(rawTrackings);
        return actualizarEstadoBulk(trackings, nuevo, motivo, changedByIgnored, force, when, devolucionSubtipoOpt);
    }

    @Transactional
    @CacheEvict(cacheNames = { "inventario", "busquedas" }, allEntries = true)
    public Map<String, Object> actualizarEstadoBulk(List<String> trackings,
                                                    PaqueteEstado nuevo,
                                                    String motivo,
                                                    String changedByIgnored,
                                                    boolean force,
                                                    Instant when,
                                                    String devolucionSubtipoOpt) {
        if (trackings == null || trackings.isEmpty())
            throw new IllegalArgumentException("Lista de trackings vacía");

        int ok = 0, fail = 0;
        List<Map<String,Object>> items = new ArrayList<>();
        for (String t : trackings) {
            try {
                Map<String, Object> r = actualizarEstadoPorTracking(t, nuevo, motivo, changedByIgnored, force, when, devolucionSubtipoOpt);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("tracking", t.toUpperCase());
                row.put("ok", true);
                row.put("nuevoEstado", nuevo.name());
                row.put("changed", r.get("changed"));
                items.add(row);
                ok++;
            } catch (Exception ex) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("tracking", t);
                row.put("ok", false);
                row.put("error", ex.getMessage());
                items.add(row);
                fail++;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", trackings.size());
        out.put("ok", ok);
        out.put("fail", fail);
        out.put("items", items);
        out.put("changed_by", actor(changedByIgnored));
        return out;
    }

    // ================== STATUS EXTERNO (SP ya existe en tu schema nuevo) ==================
    @Transactional
    @CacheEvict(cacheNames = { "inventario", "busquedas" }, allEntries = true)
    public Map<String, Object> aplicarStatusExterno(String tracking,
                                                    String statusExterno,
                                                    Instant statusAt,
                                                    String changedByIgnored) {
        if (!StringUtils.hasText(tracking)) throw new IllegalArgumentException("Tracking requerido");
        String t = tracking.trim().toUpperCase();
        if (!TRACKING_PATTERN.matcher(t).matches())
            throw new IllegalArgumentException("Formato de tracking inválido (HZCR/CR + dígitos)");
        if (!StringUtils.hasText(statusExterno)) throw new IllegalArgumentException("status externo requerido");

        String user = actor(changedByIgnored);
        initDbSession(user);

        Instant when = (statusAt != null ? statusAt : Instant.now());
        em.createNativeQuery("CALL sp_aplicar_status_externo(?, ?, ?, ?)")
          .setParameter(1, t)
          .setParameter(2, statusExterno)
          .setParameter(3, Timestamp.from(when)) // el SP aplica la resta interna
          .setParameter(4, user)
          .executeUpdate();

        // refrescar paquete (estado pudo cambiar por SP)
        Paquete p = paquetes.findByTrackingCode(t).orElseThrow();
        em.createNativeQuery("UPDATE paquetes SET cambio_en_sistema_por = :who WHERE id = :id")
          .setParameter("who", user)
          .setParameter("id", p.getId())
          .executeUpdate();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tracking", t);
        out.put("estado", p.getEstado().name());
        out.put("status_externo", statusExterno);
        out.put("status_externo_at", when);
        return out;
    }

    @Transactional
    @CacheEvict(cacheNames = { "inventario", "busquedas" }, allEntries = true)
    public Map<String, Object> aplicarStatusExternoDesdeTexto(String raw,
                                                              String statusExterno,
                                                              Instant statusAt,
                                                              String changedByIgnored) {
        List<String> trackings = extraerTrackingsDesdeTexto(raw);
        return aplicarStatusExternoBulk(trackings, statusExterno, statusAt, changedByIgnored);
    }

    @Transactional
    @CacheEvict(cacheNames = { "inventario", "busquedas" }, allEntries = true)
    public Map<String, Object> aplicarStatusExternoBulk(List<String> trackings,
                                                        String statusExterno,
                                                        Instant statusAt,
                                                        String changedByIgnored) {
        if (trackings == null || trackings.isEmpty())
            throw new IllegalArgumentException("Lista de trackings vacía");
        if (!StringUtils.hasText(statusExterno))
            throw new IllegalArgumentException("status externo requerido");

        int ok = 0, fail = 0;
        List<Map<String,Object>> items = new ArrayList<>();
        for (String t : trackings) {
            try {
                Map<String, Object> r = aplicarStatusExterno(t, statusExterno, statusAt, changedByIgnored);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("tracking", t.toUpperCase());
                row.put("ok", true);
                row.put("estado", r.get("estado"));
                row.put("status_externo", statusExterno);
                items.add(row);
                ok++;
            } catch (Exception ex) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("tracking", t);
                row.put("ok", false);
                row.put("error", ex.getMessage());
                items.add(row);
                fail++;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", trackings.size());
        out.put("ok", ok);
        out.put("fail", fail);
        out.put("items", items);
        out.put("changed_by", actor(changedByIgnored));
        return out;
    }
}
