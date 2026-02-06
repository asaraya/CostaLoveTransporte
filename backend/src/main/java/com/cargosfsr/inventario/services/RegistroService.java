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
import com.cargosfsr.inventario.model.enums.PaqueteEstado;
import com.cargosfsr.inventario.repository.DistritoRepository;
import com.cargosfsr.inventario.repository.PaqueteEstadoHistorialRepository;
import com.cargosfsr.inventario.repository.PaqueteRepository;
import com.cargosfsr.inventario.repository.SacoRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

@Service
public class RegistroService {

    // Acepta CR123, HZCR1, HZCR123456, etc.
    private static final Pattern TRACKING_PATTERN =
            Pattern.compile("^(HZCR|CR)\\d+$", Pattern.CASE_INSENSITIVE);

    private final PaqueteRepository paquetes;
    private final SacoRepository sacos;
    private final DistritoRepository distritos;
    private final PaqueteEstadoHistorialRepository historial;
    private final CurrentUser currentUser;

    @PersistenceContext
    private EntityManager em;

    public RegistroService(PaqueteRepository paquetes,
                           SacoRepository sacos,
                           DistritoRepository distritos,
                           PaqueteEstadoHistorialRepository historial,
                           CurrentUser currentUser) {
        this.paquetes = paquetes;
        this.sacos = sacos;
        this.distritos = distritos;
        this.historial = historial;
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
                                            Instant receivedAt) {

        require(StringUtils.hasText(tracking), "tracking requerido");
        require(StringUtils.hasText(marchamo), "marchamo requerido");
        require(StringUtils.hasText(distritoNombre), "distrito requerido");

        final String t = normalizeTracking(tracking);
        final String m = marchamo.trim();
        final String dname = distritoNombre.trim();

        require(TRACKING_PATTERN.matcher(t).matches(),
                "tracking inválido: debe iniciar con HZCR o CR seguido de dígitos");

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

        Instant now = Instant.now();
        Paquete p = new Paquete();
        p.setTrackingCode(t);
        p.setSaco(s);
        p.setDistrito(d);

        // equivalente al "inventario disponible" del sistema anterior
        p.setEstado(PaqueteEstado.NO_ENTREGADO_CONSIGNATARIO_DISPONIBLE);

        p.setReceivedAt(receivedAt != null ? receivedAt : now);
        p.setLastStateChangeAt(now);

        try {
            initDbSession(actor());
            paquetes.save(p); // trigger AFTER INSERT insertará historial (CREACION)

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
        return out;
    }

    @Transactional
    public void eliminarPaquetePorTracking(String tracking) {
        require(StringUtils.hasText(tracking), "tracking requerido");
        String t = normalizeTracking(tracking);
        Paquete p = paquetes.findByTrackingCode(t)
                .orElseThrow(() -> new IllegalArgumentException("No existe paquete con tracking: " + t));

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
    public Saco crearSaco(String marchamo, String defaultDistritoNombre) {
        // Mantiene compatibilidad con el controller:
        // - Crea el saco (idempotente)
        // - Si mandan distrito por el body, al menos valida que exista
        Saco s = crearSaco(marchamo);

        if (defaultDistritoNombre != null && !defaultDistritoNombre.isBlank()) {
            String d = defaultDistritoNombre.trim();
            distritos.findByNombre(d)
                .orElseThrow(() -> new IllegalArgumentException("El distrito no existe: " + d));
        }

        return s;
    }

}
