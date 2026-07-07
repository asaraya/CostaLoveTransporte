package com.cargosfsr.inventario.services;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.cargosfsr.inventario.auth.CurrentUser;
import com.cargosfsr.inventario.model.Distrito;
import com.cargosfsr.inventario.model.Paquete;
import com.cargosfsr.inventario.model.Saco;
import com.cargosfsr.inventario.model.Ubicacion;
import com.cargosfsr.inventario.model.enums.PaqueteEstado;
import com.cargosfsr.inventario.repository.DistritoRepository;
import com.cargosfsr.inventario.repository.PaqueteEstadoHistorialRepository;
import com.cargosfsr.inventario.repository.PaqueteRepository;
import com.cargosfsr.inventario.repository.SacoRepository;
import com.cargosfsr.inventario.repository.UbicacionRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

@Service
public class RegistroService {

    // Acepta CR..., HZCR... y LM... sin límite artificial de longitud.
    private static final Pattern TRACKING_PATTERN =
            Pattern.compile("^(HZCR|CR|LM)[A-Z0-9]+$", Pattern.CASE_INSENSITIVE);

    private final PaqueteRepository paquetes;
    private final SacoRepository sacos;
    private final DistritoRepository distritos;
    private final UbicacionRepository ubicaciones;
    private final PaqueteEstadoHistorialRepository historial;
    private final PaqueteAuditService auditService;
    private final CurrentUser currentUser;

    @PersistenceContext
    private EntityManager em;

    public RegistroService(PaqueteRepository paquetes,
                           SacoRepository sacos,
                           DistritoRepository distritos,
                           UbicacionRepository ubicaciones,
                           PaqueteEstadoHistorialRepository historial,
                           PaqueteAuditService auditService,
                           CurrentUser currentUser) {
        this.paquetes = paquetes;
        this.sacos = sacos;
        this.distritos = distritos;
        this.ubicaciones = ubicaciones;
        this.historial = historial;
        this.auditService = auditService;
        this.currentUser = currentUser;
    }

    private String actor() { return currentUser.display(); }

    private static String normalizeTracking(String raw) {
        return raw.trim().toUpperCase();
    }

    private static void require(boolean cond, String msg) {
        if (!cond) throw new IllegalArgumentException(msg);
    }

    /** Inicializa la sesión SQL con TZ CR y el usuario para triggers */
    private void initDbSession(String who) {
        em.createNativeQuery("SET time_zone = '-06:00'").executeUpdate();
        em.createNativeQuery("SET @changed_by := :who")
          .setParameter("who", who)
          .executeUpdate();
    }

    @Transactional
    public Map<String, Object> preregistrar(String tracking,
                                            String marchamo,
                                            String distritoNombre,
                                            String ubicacionCodigo,
                                            Instant receivedAt) {

        require(StringUtils.hasText(tracking), "tracking requerido");
        require(StringUtils.hasText(marchamo), "marchamo requerido");
        require(StringUtils.hasText(distritoNombre), "distrito requerido");
        require(StringUtils.hasText(ubicacionCodigo), "ubicacionCodigo requerido");

        final String t = normalizeTracking(tracking);
        final String m = marchamo.trim();
        final String dname = distritoNombre.trim();
        final String ucode = ubicacionCodigo.trim();

        require(TRACKING_PATTERN.matcher(t).matches(),
                "tracking inválido: debe iniciar con CR, HZCR o LM seguido de caracteres alfanuméricos");

        if (paquetes.findByTrackingCode(t).isPresent()) {
            throw new IllegalArgumentException("No se pueden ingresar trackings repetidos: " + t);
        }

        // Validar saco existente (flujo actual)
        Saco s = sacos.findByMarchamo(m)
                .orElseThrow(() -> new IllegalArgumentException(
                        "El marchamo no existe: " + m + " (debe crearse previamente)"));

        // Validar distrito existente
        Distrito d = distritos.findByNombre(dname)
                .orElseThrow(() -> new IllegalArgumentException(
                        "El distrito no existe: " + dname));

        // Validar ubicación/mueble existente
        Ubicacion u = ubicaciones.findByCodigo(ucode)
                .orElseThrow(() -> new IllegalArgumentException(
                        "La ubicación no existe: " + ucode));

        Instant now = Instant.now();
        Paquete p = new Paquete();
        p.setTrackingCode(t);
        p.setSaco(s);
        p.setDistrito(d);
        p.setUbicacion(u);

        // ✅ REGLA NUEVA: al hacer recepción, el estado por defecto es ENTREGADO_A_TRANSPORTISTA_LOCAL
        p.setEstado(PaqueteEstado.ENTREGADO_A_TRANSPORTISTA_LOCAL);

        p.setReceivedAt(receivedAt != null ? receivedAt : now);
        p.setLastStateChangeAt(now);

        try {
            initDbSession(actor());
            paquetes.save(p);
            auditService.registrarCreacion(p, "RECEPCION", actor());

            // Ajuste de hora en BD: mover -6h (DB hace la conversión)
            em.createNativeQuery("""
                UPDATE paquetes
                   SET received_at = CASE WHEN received_at IS NOT NULL
                                          THEN DATE_SUB(received_at, INTERVAL 6 HOUR)
                                          ELSE received_at END,
                       last_state_change_at = DATE_SUB(last_state_change_at, INTERVAL 6 HOUR)
                 WHERE id = :id
            """).setParameter("id", p.getId())
              .executeUpdate();

        } catch (DataIntegrityViolationException ex) {
            String msg = ex.getMessage();
            if (msg != null && msg.toLowerCase().contains("uk_paquetes_tracking")) {
                throw new IllegalStateException("tracking ya existe en base: " + t);
            }
            throw ex;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tracking", t);
        out.put("paquete_id", p.getId());
        out.put("estado", p.getEstado().name());
        out.put("received_at", p.getReceivedAt());
        out.put("saco_id", s.getId());
        out.put("marchamo", s.getMarchamo());
        out.put("distrito_id", d.getId());
        out.put("distrito_nombre", d.getNombre());
        out.put("ubicacion_id", u.getId());
        out.put("ubicacion_codigo", u.getCodigo());
        return out;
    }

    @Transactional
    public void eliminarPaquetePorTracking(String tracking) {
        require(StringUtils.hasText(tracking), "tracking requerido");
        String t = normalizeTracking(tracking);
        Paquete p = paquetes.findByTrackingCode(t)
                .orElseThrow(() -> new IllegalArgumentException("No existe paquete con tracking: " + t));

        auditService.registrarEliminacion(p, "RECEPCION", actor());
        historial.deleteByPaqueteId(p.getId());
        paquetes.delete(p);
    }

    // ====== SACOS ======
    @Transactional
    public Saco crearSaco(String marchamo) {
        if (marchamo == null || marchamo.isBlank()) throw new IllegalArgumentException("marchamo requerido");
        String m = marchamo.trim();

        Optional<Saco> existing = sacos.findByMarchamo(m);
        if (existing.isPresent()) return existing.get();

        Saco s = new Saco();
        s.setMarchamo(m);
        return sacos.save(s);
    }

    @Transactional
    public void eliminarSacoVacio(String marchamo) {
        if (marchamo == null || marchamo.isBlank()) throw new IllegalArgumentException("marchamo requerido");
        String m = marchamo.trim();

        Saco s = sacos.findByMarchamo(m)
            .orElseThrow(() -> new IllegalArgumentException("No existe saco con marchamo: " + m));

        long n = paquetes.countBySacoId(s.getId());
        if (n > 0) {
            throw new IllegalStateException("El marchamo " + m + " tiene " + n + " paquete(s); no se puede eliminar.");
        }
        sacos.delete(s);
    }

    // ===== Eliminación en lote por tracking =====
    @Transactional
    public Map<String, Object> eliminarPaquetesEnLote(java.util.List<String> rawTrackings) {
        require(rawTrackings != null && !rawTrackings.isEmpty(), "trackings requeridos");

        java.util.List<String> trackings = rawTrackings.stream()
                .filter(StringUtils::hasText)
                .map(RegistroService::normalizeTracking)
                .distinct()
                .toList();

        if (trackings.isEmpty()) {
            return Map.of(
                    "ok", true,
                    "solicitados", 0,
                    "eliminados", 0,
                    "no_encontrados", java.util.List.of()
            );
        }

        initDbSession(actor());

        java.util.List<Paquete> existentes = paquetes.findByTrackingCodeIn(trackings);
        java.util.Set<String> existentesSet = existentes.stream()
                .map(Paquete::getTrackingCode)
                .collect(java.util.stream.Collectors.toSet());

        java.util.List<Long> ids = existentes.stream().map(Paquete::getId).toList();

        for (Paquete p : existentes) {
            auditService.registrarEliminacion(p, "RECEPCION", actor());
        }

        if (!ids.isEmpty()) {
            historial.deleteByPaqueteIdIn(ids);
            paquetes.deleteAllByIdInBatch(ids);
        }

        java.util.List<String> noEncontrados = trackings.stream()
                .filter(t -> !existentesSet.contains(t))
                .toList();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("solicitados", trackings.size());
        out.put("eliminados", ids.size());
        out.put("no_encontrados", noEncontrados);
        return out;
    }

    @Transactional
    public Saco crearSaco(String marchamo, String defaultDistritoNombre, String defaultUbicacionCodigo) {
        // Crea el saco de forma idempotente.
        // El distrito se valida por compatibilidad con el flujo actual; la ubicación queda como default del saco.
        Saco s = crearSaco(marchamo);

        if (defaultDistritoNombre != null && !defaultDistritoNombre.isBlank()) {
            String d = defaultDistritoNombre.trim();
            distritos.findByNombre(d)
                .orElseThrow(() -> new IllegalArgumentException("El distrito no existe: " + d));
        }

        if (defaultUbicacionCodigo != null && !defaultUbicacionCodigo.isBlank()) {
            String ucode = defaultUbicacionCodigo.trim();
            Ubicacion u = ubicaciones.findByCodigo(ucode)
                .orElseThrow(() -> new IllegalArgumentException("Ubicación no existe: " + ucode));
            s.setDefaultUbicacion(u);
            return sacos.save(s);
        }

        return s;
    }

    @Transactional
    public Saco crearSaco(String marchamo, String defaultDistritoNombre) {
        return crearSaco(marchamo, defaultDistritoNombre, null);
    }

}
