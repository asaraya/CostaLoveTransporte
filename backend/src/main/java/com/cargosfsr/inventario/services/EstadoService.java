package com.cargosfsr.inventario.services;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
import com.cargosfsr.inventario.model.Usuario;
import com.cargosfsr.inventario.model.enums.DevolucionSubtipo;
import com.cargosfsr.inventario.model.enums.PaqueteEstado;
import com.cargosfsr.inventario.repository.PaqueteEstadoHistorialRepository;
import com.cargosfsr.inventario.repository.PaqueteRepository;
import com.cargosfsr.inventario.repository.UsuarioRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

@Service
public class EstadoService {

    private final CurrentUser currentUser;

    private static final Pattern TRACKING_PATTERN =
            Pattern.compile("(?:HZCR|CR|LM)[A-Z0-9]+", Pattern.CASE_INSENSITIVE);

    /**
     * Mensajería operativa:
     * - MENSAJERO
     * - TRANSPORTISTA (y cualquier variante que empiece con TRANSPORTISTA: "TRANSPORTISTA.", "TRANSPORTISTA LOCAL", etc.)
     */
    private static final String ROL_MENSAJERO = "MENSAJERO";
    private static final String ROLE_TRANSPORTISTA_PREFIX = "TRANSPORTISTA";

    private final PaqueteRepository paquetes;
    private final PaqueteEstadoHistorialRepository historial;
    private final PaqueteAuditService auditService;
    private final UsuarioRepository usuarios;

    @PersistenceContext
    private EntityManager em;

    public EstadoService(PaqueteRepository paquetes,
                         PaqueteEstadoHistorialRepository historial,
                         PaqueteAuditService auditService,
                         UsuarioRepository usuarios,
                         CurrentUser currentUser) {
        this.currentUser = currentUser;
        this.paquetes = paquetes;
        this.historial = historial;
        this.auditService = auditService;
        this.usuarios = usuarios;
    }

    private boolean isRolMensajeria(String r) {
        if (!StringUtils.hasText(r)) return false;
        String up = r.trim().toUpperCase(Locale.ROOT);
        return up.equals(ROL_MENSAJERO) || up.startsWith(ROLE_TRANSPORTISTA_PREFIX);
    }

    private boolean usuarioEsMensajeria(Usuario u) {
        // Compat: hay instalaciones donde el rol viene en u.rol y otras donde viene en u.role
        return isRolMensajeria(u.getRol()) || isRolMensajeria(u.getRole());
    }

    /**
     * Lista usuarios activos con rol de mensajería.
     * Compat: acepta MENSAJERO/TRANSPORTISTA tanto en columna "rol" como en "role".
     */
    private List<Usuario> findMensajerosActivos() {
        // Importante: tolerante a variaciones como "TRANSPORTISTA." / "TRANSPORTISTA LOCAL" y a espacios.
        // Se busca tanto en columna "rol" (operativo) como "role" (login/permisos).
        return usuarios.findMensajeriaActiva();
    }

    private Usuario requireMensajero(Long mensajeroId) {
        if (mensajeroId == null) {
            throw new IllegalArgumentException("mensajeroId requerido para PRUEBA_DE_ENTREGA");
        }
        Usuario u = usuarios.findById(mensajeroId).orElseThrow(
            () -> new IllegalArgumentException("No existe mensajero con id: " + mensajeroId)
        );
        if (u.getActive() != null && !u.getActive()) {
            throw new IllegalArgumentException("El mensajero/transportista está inactivo: " + u.getFullName());
        }
        if (!usuarioEsMensajeria(u)) {
            throw new IllegalArgumentException("El usuario no tiene rol MENSAJERO/TRANSPORTISTA: " + u.getFullName());
        }
        return u;
    }

    /** Lista mensajeros/transportistas activos para que el FE muestre el selector */
    public List<Map<String, Object>> listarMensajerosActivos() {
        List<Usuario> list = findMensajerosActivos();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Usuario u : list) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", u.getId());
            m.put("username", u.getUsername());
            m.put("fullName", u.getFullName());
            out.add(m);
        }
        return out;
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
                                                           String devolucionSubtipoOpt,
                                                           Long mensajeroId) {
        if (!StringUtils.hasText(tracking)) throw new IllegalArgumentException("Tracking requerido");
        String t = tracking.trim().toUpperCase();
        if (!TRACKING_PATTERN.matcher(t).matches())
            throw new IllegalArgumentException("Formato de tracking inválido (CR/HZCR/LM + caracteres alfanuméricos)");

        Paquete p = paquetes.findByTrackingCode(t).orElseThrow(
            () -> new IllegalArgumentException("No existe paquete con tracking: " + t)
        );

        String user = actor(changedByIgnored);
        initDbSession(user);

        PaqueteEstado anterior = p.getEstado();
        DevolucionSubtipo subtipoAnterior = p.getDevolucionSubtipo();
        Usuario mensajeroAnterior = p.getMensajero();
        String mensajeroAnteriorNombre = mensajeroAnterior != null ? mensajeroAnterior.getFullName() : null;
        Instant ts = (when != null ? when : Instant.now());

        boolean touchedDelivered = false; // delivered_at = ENTREGADO a la PERSONA (PRUEBA_DE_ENTREGA)
        boolean touchedReturned  = false;
        boolean touchedMensajero = false;

        Usuario mensajero = null;

        DevolucionSubtipo sub = null;
        if (devolucionSubtipoOpt != null && !devolucionSubtipoOpt.isBlank()) {
            sub = DevolucionSubtipo.valueOf(devolucionSubtipoOpt.trim().toUpperCase());
        }

        // ===== Reglas de negocio =====
        // - Estados "ENTREGADO_A_TRANSPORTISTA_*" y "NO_ENTREGADO_CONSIGNATARIO_DISPONIBLE" NO son entrega final.
        // - PRUEBA_DE_ENTREGA SÍ significa entregado a la persona => set delivered_at
        // - NO_ENTREGABLE significa devolución => set returned_at

        if (nuevo == PaqueteEstado.PRUEBA_DE_ENTREGA) {
            mensajero = requireMensajero(mensajeroId);
            p.setMensajero(mensajero);
            p.setResponsableConsolidado(mensajero.getFullName());
            touchedMensajero = true;

            p.setDeliveredAt(ts);
            touchedDelivered = true;

            // No puede estar devuelto y entregado final a la vez
            if (p.getReturnedAt() != null) {
                p.setReturnedAt(null);
            }

        } else if (nuevo == PaqueteEstado.NO_ENTREGABLE) {
            // si se marca como devolución, ya no aplica mensajero
            if (p.getMensajero() != null) {
                p.setMensajero(null);
                touchedMensajero = true;
            }

            // cambio agregado: no puede quedar delivered_at si ahora pasa a NO_ENTREGABLE
            if (p.getDeliveredAt() != null) {
                p.setDeliveredAt(null);
            }

            p.setReturnedAt(ts);
            touchedReturned = true;
            p.setDevolucionSubtipo(sub != null ? sub : (p.getDevolucionSubtipo() != null ? p.getDevolucionSubtipo() : DevolucionSubtipo.FUERA_DE_RUTA));

        } else if (nuevo == PaqueteEstado.NO_ENTREGADO_CONSIGNATARIO_DISPONIBLE && force) {
            // reset a "disponible": limpia entrega/devolución
            if (p.getDeliveredAt() != null || p.getReturnedAt() != null) {
                p.setDeliveredAt(null);
                p.setReturnedAt(null);
            }

            // al resetear, también limpiamos mensajero
            if (p.getMensajero() != null) {
                p.setMensajero(null);
                touchedMensajero = true;
            }
        } else {
            // Cualquier otro estado NO es entrega final => no debe quedar mensajero en el paquete
            if (p.getMensajero() != null) {
                p.setMensajero(null);
                touchedMensajero = true;
            }

            // cambio agregado: cualquier otro estado tampoco debe conservar marcas finales inválidas
            if (p.getDeliveredAt() != null) {
                p.setDeliveredAt(null);
            }
            if (p.getReturnedAt() != null) {
                p.setReturnedAt(null);
            }
        }

        boolean changesState = (anterior != nuevo) ||
                               (nuevo == PaqueteEstado.NO_ENTREGADO_CONSIGNATARIO_DISPONIBLE && force);
        boolean changesSubtipo = subtipoAnterior != p.getDevolucionSubtipo();

        if (!changesState && !changesSubtipo) {
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

        if (!changesState && changesSubtipo) {
            p.setLastStateChangeAt(ts);
            paquetes.save(p);

            em.createNativeQuery("""
                UPDATE paquetes
                   SET last_state_change_at = DATE_SUB(:ts, INTERVAL 6 HOUR),
                       cambio_en_sistema_por = :who
                 WHERE id = :id
            """)
            .setParameter("ts", Timestamp.from(ts))
            .setParameter("who", user)
            .setParameter("id", p.getId())
            .executeUpdate();

            auditService.registrarCambioSubtipoDevolucion(
                p,
                nuevo.name(),
                subtipoAnterior != null ? subtipoAnterior.name() : null,
                p.getDevolucionSubtipo() != null ? p.getDevolucionSubtipo().name() : null,
                auditService.moduloDesdeMotivo(motivo),
                motivo,
                user
            );

            if (touchedMensajero) {
                String mensajeroNuevoNombre = p.getMensajero() != null ? p.getMensajero().getFullName() : null;
                auditService.registrar(
                    p.getId(),
                    t,
                    "CAMBIO_MENSAJERO",
                    auditService.moduloDesdeMotivo(motivo),
                    "Se cambió el mensajero del paquete " + t + " de " + (mensajeroAnteriorNombre != null ? mensajeroAnteriorNombre : "Sin mensajero") + " a " + (mensajeroNuevoNombre != null ? mensajeroNuevoNombre : "Sin mensajero") + ".",
                    "mensajero",
                    mensajeroAnteriorNombre,
                    mensajeroNuevoNombre,
                    user,
                    null
                );
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("tracking", t);
            out.put("estado_anterior", anterior != null ? anterior.name() : null);
            out.put("estado_nuevo", nuevo.name());
            out.put("changed", true);
            out.put("subtipo_changed", true);
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

        // mensajero (solo aplica cuando PRUEBA_DE_ENTREGA)
        if (touchedMensajero) {
            if (nuevo == PaqueteEstado.PRUEBA_DE_ENTREGA) {
                sql.append(", mensajero_id = :mensajeroId, responsable_consolidado = :mensajeroName");
            } else {
                sql.append(", mensajero_id = NULL");
            }
        }

        // si se marca como entregado a persona, limpia devolución
        if (nuevo == PaqueteEstado.PRUEBA_DE_ENTREGA) {
            sql.append(", returned_at = NULL");
        }

        // reset forzado (limpiar)
        if (nuevo == PaqueteEstado.NO_ENTREGADO_CONSIGNATARIO_DISPONIBLE && force) {
            sql.append(", delivered_at = NULL, returned_at = NULL");
        }

        sql.append(" WHERE id = :id");

        var q = em.createNativeQuery(sql.toString())
          .setParameter("ts", Timestamp.from(ts))
          .setParameter("who", user)
          .setParameter("id", p.getId());

        // Solo bindeamos los parámetros si el SQL los incluye
        if (touchedMensajero && nuevo == PaqueteEstado.PRUEBA_DE_ENTREGA) {
            q.setParameter("mensajeroId", mensajero.getId());
            q.setParameter("mensajeroName", mensajero.getFullName());
        }

        q.executeUpdate();

        PaqueteEstadoHistorial h = new PaqueteEstadoHistorial();
        h.setPaquete(p);
        h.setEstadoFrom(anterior);
        h.setEstadoTo(nuevo);
        h.setChangedAt(ts);
        h.setMotivo(motivo);
        h.setChangedBy(user);

        // guardar mensajero en historial cuando es entrega final
        if (nuevo == PaqueteEstado.PRUEBA_DE_ENTREGA) {
            h.setMensajero(mensajero);
        }
        historial.save(h);

        em.createNativeQuery("""
            UPDATE paquete_estado_historial
               SET changed_at = DATE_SUB(changed_at, INTERVAL 6 HOUR)
             WHERE id = :id
        """).setParameter("id", h.getId())
          .executeUpdate();

        auditService.registrarCambioEstado(
            p,
            anterior != null ? anterior.name() : null,
            nuevo.name(),
            subtipoAnterior != null ? subtipoAnterior.name() : null,
            p.getDevolucionSubtipo() != null ? p.getDevolucionSubtipo().name() : null,
            auditService.moduloDesdeMotivo(motivo),
            motivo,
            user
        );

        if (touchedMensajero) {
            String mensajeroNuevoNombre = p.getMensajero() != null ? p.getMensajero().getFullName() : null;
            auditService.registrar(
                p.getId(),
                t,
                "CAMBIO_MENSAJERO",
                auditService.moduloDesdeMotivo(motivo),
                "Se cambió el mensajero del paquete " + t + " de " + (mensajeroAnteriorNombre != null ? mensajeroAnteriorNombre : "Sin mensajero") + " a " + (mensajeroNuevoNombre != null ? mensajeroNuevoNombre : "Sin mensajero") + ".",
                "mensajero",
                mensajeroAnteriorNombre,
                mensajeroNuevoNombre,
                user,
                null
            );
        }

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
        out.put("mensajero_id", p.getMensajero() != null ? p.getMensajero().getId() : null);
        out.put("mensajero", p.getMensajero() != null ? p.getMensajero().getFullName() : null);
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
                                                          String devolucionSubtipoOpt,
                                                          Long mensajeroId) {
        List<String> trackings = extraerTrackingsDesdeTexto(rawTrackings);
        return actualizarEstadoBulk(trackings, nuevo, motivo, changedByIgnored, force, when, devolucionSubtipoOpt, mensajeroId);
    }

    @Transactional
    @CacheEvict(cacheNames = { "inventario", "busquedas" }, allEntries = true)
    public Map<String, Object> actualizarEstadoBulk(List<String> trackings,
                                                    PaqueteEstado nuevo,
                                                    String motivo,
                                                    String changedByIgnored,
                                                    boolean force,
                                                    Instant when,
                                                    String devolucionSubtipoOpt,
                                                    Long mensajeroId) {
        if (trackings == null || trackings.isEmpty())
            throw new IllegalArgumentException("Lista de trackings vacía");

        int ok = 0, fail = 0;
        List<Map<String,Object>> items = new ArrayList<>();
        for (String t : trackings) {
            try {
                Map<String, Object> r = actualizarEstadoPorTracking(
                        t,
                        nuevo,
                        motivo,
                        changedByIgnored,
                        force,
                        when,
                        devolucionSubtipoOpt,
                        mensajeroId
                );
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
            throw new IllegalArgumentException("Formato de tracking inválido (CR/HZCR/LM + caracteres alfanuméricos)");
        if (!StringUtils.hasText(statusExterno)) throw new IllegalArgumentException("status externo requerido");

        String user = actor(changedByIgnored);
        initDbSession(user);

        Paquete antes = paquetes.findByTrackingCode(t).orElseThrow();
        PaqueteEstado estadoAnterior = antes.getEstado();
        DevolucionSubtipo subtipoAnterior = antes.getDevolucionSubtipo();
        Instant when = (statusAt != null ? statusAt : Instant.now());
        em.createNativeQuery("CALL sp_aplicar_status_externo(?, ?, ?, ?)")
          .setParameter(1, t)
          .setParameter(2, statusExterno)
          .setParameter(3, Timestamp.from(when)) // el SP aplica la resta interna
          .setParameter(4, user)
          .executeUpdate();

        em.clear();
        Paquete p = paquetes.findByTrackingCode(t).orElseThrow();
        em.createNativeQuery("UPDATE paquetes SET cambio_en_sistema_por = :who WHERE id = :id")
          .setParameter("who", user)
          .setParameter("id", p.getId())
          .executeUpdate();

        boolean cambioEstado = estadoAnterior != p.getEstado();
        boolean cambioSubtipo = subtipoAnterior != p.getDevolucionSubtipo();

        if (cambioEstado) {
            auditService.registrarCambioEstado(
                p,
                estadoAnterior != null ? estadoAnterior.name() : null,
                p.getEstado().name(),
                subtipoAnterior != null ? subtipoAnterior.name() : null,
                p.getDevolucionSubtipo() != null ? p.getDevolucionSubtipo().name() : null,
                "STATUS_EXTERNO",
                "AUTO: status externo - " + statusExterno,
                user
            );
        } else if (cambioSubtipo) {
            auditService.registrarCambioSubtipoDevolucion(
                p,
                p.getEstado() != null ? p.getEstado().name() : null,
                subtipoAnterior != null ? subtipoAnterior.name() : null,
                p.getDevolucionSubtipo() != null ? p.getDevolucionSubtipo().name() : null,
                "STATUS_EXTERNO",
                "AUTO: status externo - " + statusExterno,
                user
            );
        } else {
            auditService.registrar(p.getId(), t, "ACTUALIZACION_STATUS_EXTERNO", "STATUS_EXTERNO", "Se actualizó el status externo del paquete " + t + " desde STATUS_EXTERNO.", "status_externo", null, statusExterno, user, null);
        }

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