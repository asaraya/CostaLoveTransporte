// src/main/java/com/cargosfsr/inventario/services/ConsultasService.java
package com.cargosfsr.inventario.services;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.simple.SimpleJdbcCall;
import org.springframework.stereotype.Service;

@Service
public class ConsultasService {

    private final JdbcTemplate jdbc;

    public ConsultasService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Regla de negocio:
     * - EN_INVENTARIO = cualquier paquete cuyo estado esté en cualquiera de estos 3 estados.
     * - NO_ENTREGABLE (cualquier subtipo) = fuera de inventario.
     */
    private static final String SQL_ESTADOS_EN_INVENTARIO =
            "('ENTREGADO_A_TRANSPORTISTA_LOCAL','NO_ENTREGADO_CONSIGNATARIO_DISPONIBLE','ENTREGADO_A_TRANSPORTISTA_LOCAL_2DO_INTENTO')";

    private static final List<String> ESTADOS_EN_INVENTARIO = List.of(
            "ENTREGADO_A_TRANSPORTISTA_LOCAL",
            "NO_ENTREGADO_CONSIGNATARIO_DISPONIBLE",
            "ENTREGADO_A_TRANSPORTISTA_LOCAL_2DO_INTENTO"
    );

    private Timestamp ts(Instant i) { return i == null ? null : Timestamp.from(i); }

    /**
     * Soporta:
     * - null/blank => null
     * - "TODOS" => null (sin filtro)
     * - "EN_INVENTARIO" / "INVENTARIO" => lista de 3 estados
     * - "A,B,C" (CSV) => lista [A,B,C]
     * - "A" => lista [A]
     */
    private static List<String> parseEstados(String raw) {
        if (raw == null || raw.isBlank()) return null;

        String t = raw.trim().toUpperCase(Locale.ROOT);

        if ("TODOS".equals(t)) return null;

        if ("EN_INVENTARIO".equals(t) || "INVENTARIO".equals(t)) {
            return ESTADOS_EN_INVENTARIO;
        }

        // CSV o uno solo
        List<String> out = Arrays.stream(raw.split(","))
                .map(s -> s == null ? "" : s.trim().toUpperCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .distinct()
                .toList();

        return out.isEmpty() ? null : out;
    }

    private static long asLong(Object v) {
        if (v == null) return -1L;
        if (v instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (Exception ignore) {
            return -1L;
        }
    }

    /* ==========================
     * INVENTARIO PAGINADO (estado o TODOS)
     * default = NO_ENTREGADO_CONSIGNATARIO_DISPONIBLE
     * ========================== */
    public List<Map<String, Object>> inventarioPaginado(String estado, int limit, int offset) {
        int lim = Math.max(1, Math.min(limit, 1000));
        int off = Math.max(0, offset);

        String est = (estado == null || estado.isBlank())
                ? "NO_ENTREGADO_CONSIGNATARIO_DISPONIBLE"
                : estado.trim().toUpperCase(Locale.ROOT);

        final String selectCols = """
            SELECT
                v.id,
                v.tracking_code,
                v.recipient_name,
                v.recipient_address,
                v.recipient_phone,
                v.merchandise_value,
                v.content_description,
                v.estado,
                v.devolucion_subtipo,
                v.received_at,
                v.delivered_at,
                v.returned_at,
                v.last_state_change_at,
                v.status_externo,
                v.status_externo_at,
                v.ultimo_cambio_por,
                v.responsable_consolidado,
                v.observaciones,
                v.saco_id,
                v.marchamo,
                v.distrito_id,
                v.distrito_nombre,
                (
                  SELECT h.changed_by
                    FROM paquete_estado_historial h
                   WHERE h.paquete_id = v.id
                   ORDER BY h.changed_at DESC, h.id DESC
                   LIMIT 1
                ) AS last_changed_by
            FROM vw_paquete_resumen v
        """;

        if ("TODOS".equals(est)) {
            final String sql = selectCols + " ORDER BY v.id DESC LIMIT ? OFFSET ?";
            return jdbc.queryForList(sql, lim, off);
        }

        // EN_INVENTARIO = 3 estados (no incluye NO_ENTREGABLE)
        if ("EN_INVENTARIO".equals(est) || "INVENTARIO".equals(est)) {
            final String sql = selectCols + " WHERE v.estado IN " + SQL_ESTADOS_EN_INVENTARIO +
                    " ORDER BY v.id DESC LIMIT ? OFFSET ?";
            return jdbc.queryForList(sql, lim, off);
        }

        final String sql = selectCols + " WHERE v.estado = ? ORDER BY v.id DESC LIMIT ? OFFSET ?";
        return jdbc.queryForList(sql, est, lim, off);
    }

    /* ==========================
     * CONTADORES (para UI)
     * ========================== */

    @Cacheable(cacheNames = "inventario", key = "'cnt_inventario:'+ #estado")
    public long countInventario(String estado) {
        String est = (estado == null || estado.isBlank())
                ? "NO_ENTREGADO_CONSIGNATARIO_DISPONIBLE"
                : estado.trim().toUpperCase(Locale.ROOT);

        if ("TODOS".equals(est)) {
            return jdbc.queryForObject("SELECT COUNT(*) FROM paquetes", Long.class);
        }

        if ("EN_INVENTARIO".equals(est) || "INVENTARIO".equals(est)) {
            return jdbc.queryForObject(
                    "SELECT COUNT(*) FROM paquetes WHERE estado IN " + SQL_ESTADOS_EN_INVENTARIO,
                    Long.class);
        }

        return jdbc.queryForObject("SELECT COUNT(*) FROM paquetes WHERE estado = ?", Long.class, est);
    }

    @Cacheable(cacheNames = "inventario", key = "'cnt_marchamo:'+ #marchamo")
    public long countPorMarchamo(String marchamo) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM vw_paquete_resumen WHERE marchamo = ?", Long.class, marchamo);
    }

    @Cacheable(cacheNames = "inventario", key = "'cnt_distrito:'+ #nombre")
    public long countPorDistritoNombre(String nombre) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM vw_paquete_resumen WHERE distrito_nombre = ?",
                Long.class, nombre);
    }

    @Cacheable(cacheNames = "busquedas", key = "'cnt_tracking:'+ #q +':'+ #like")
    public long countPorTracking(String q, int like) {
        if (like == 1) {
            return jdbc.queryForObject(
                    "SELECT COUNT(*) FROM vw_paquete_resumen WHERE tracking_code LIKE CONCAT('%', ?, '%')",
                    Long.class, q);
        }
        return jdbc.queryForObject("SELECT COUNT(*) FROM vw_paquete_resumen WHERE tracking_code = ?",
                Long.class, q);
    }

    @Cacheable(cacheNames = "busquedas", key = "'cnt_nombre:'+ #q +':'+ #like")
    public long countPorNombre(String q, int like) {
        if (like == 1) {
            return jdbc.queryForObject(
                    "SELECT COUNT(*) FROM vw_paquete_resumen WHERE recipient_name LIKE CONCAT('%', ?, '%')",
                    Long.class, q);
        }
        return jdbc.queryForObject("SELECT COUNT(*) FROM vw_paquete_resumen WHERE recipient_name = ?",
                Long.class, q);
    }

    @Cacheable(cacheNames = "busquedas", key = "'cnt_direccion:'+ #q +':'+ #like")
    public long countPorDireccion(String q, int like) {
        if (like == 1) {
            return jdbc.queryForObject(
                    "SELECT COUNT(*) FROM vw_paquete_resumen WHERE recipient_address LIKE CONCAT('%', ?, '%')",
                    Long.class, q);
        }
        return jdbc.queryForObject("SELECT COUNT(*) FROM vw_paquete_resumen WHERE recipient_address = ?",
                Long.class, q);
    }

    /* ==========================
     * REPORTES (ENTREGADOS / NO ENTREGABLE)
     * ========================== */

    public List<Map<String, Object>> entregados(Instant desde, Instant hasta, String marchamo) {
        Timestamp pDesde = ts(desde);
        Timestamp pHasta = ts(hasta);
        String pMarchamo = (marchamo == null || marchamo.isBlank()) ? null : marchamo;
        return jdbc.queryForList("CALL sp_paquetes_entregados(?, ?, ?)", pDesde, pHasta, pMarchamo);
    }

    public List<Map<String, Object>> devolucion(Instant desde, Instant hasta, String marchamo, String subtipo) {
        Timestamp pDesde = ts(desde);
        Timestamp pHasta = ts(hasta);
        String pMarchamo = (marchamo == null || marchamo.isBlank()) ? null : marchamo;
        String pSubtipo  = (subtipo  == null || subtipo.isBlank())  ? "ALL" : subtipo.toUpperCase(Locale.ROOT);
        return jdbc.queryForList("CALL sp_paquetes_devolucion(?, ?, ?, ?)", pDesde, pHasta, pMarchamo, pSubtipo);
    }

    /* ==========================
     * BÚSQUEDAS / FILTROS
     * ========================== */

    @Cacheable(cacheNames = "inventario", key = "'estado:'+ #estado + ':' + #tipoFecha + ':' + #desde + ':' + #hasta")
    public List<Map<String, Object>> porEstado(String estado, String tipoFecha, Instant desde, Instant hasta) {
        Timestamp pDesde = ts(desde);
        Timestamp pHasta = ts(hasta);
        String pTipo = (tipoFecha == null ? "CAMBIO" : tipoFecha);
        return jdbc.queryForList("CALL sp_paquetes_por_estado(?, ?, ?, ?)", estado, pTipo, pDesde, pHasta);
    }

    @Cacheable(cacheNames = "inventario", key = "'distrito:'+ #nombre")
    public List<Map<String, Object>> porDistritoNombre(String nombre) {
        // SP: (p_distrito_nombre, p_tipo_fecha, p_desde, p_hasta, p_estado)
        return jdbc.queryForList("CALL sp_paquetes_por_distrito(?, ?, ?, ?, ?)", nombre, null, null, null, null);
    }

    @Cacheable(cacheNames = "busquedas", key = "'nom_exact_like:'+ #nombre +':'+ #like")
    public List<Map<String, Object>> porNombre(String nombre, int like) {
        int pLike = like == 0 ? 0 : 1;
        if (pLike == 1) {
            return jdbc.queryForList(
                    "SELECT * FROM vw_paquete_resumen WHERE recipient_name LIKE CONCAT('%', ?, '%') ORDER BY id DESC",
                    nombre);
        }
        return jdbc.queryForList(
                "SELECT * FROM vw_paquete_resumen WHERE recipient_name = ? ORDER BY id DESC",
                nombre);
    }

    @Cacheable(cacheNames = "busquedas", key = "'nom:'+ #nombre")
    public List<Map<String, Object>> porNombreContiene(String nombre) {
        return porNombre(nombre, 1);
    }

    @Cacheable(cacheNames = "busquedas", key = "'dir_exact_like:'+ #dir +':'+ #like")
    public List<Map<String, Object>> porDireccion(String dir, int like) {
        int pLike = like == 0 ? 0 : 1;
        // SP existe en tu schema nuevo
        return jdbc.queryForList("CALL sp_paquetes_por_direccion(?, ?)", dir, pLike);
    }

    @Cacheable(cacheNames = "busquedas", key = "'dir:'+ #dir")
    public List<Map<String, Object>> porDireccionContiene(String dir) {
        return porDireccion(dir, 1);
    }

    @Cacheable(cacheNames = "busquedas", key = "'trk_exact_like:'+ #tracking +':'+ #like")
    public List<Map<String, Object>> porTracking(String tracking, int like) {
        int pLike = like == 0 ? 0 : 1;
        if (pLike == 1) {
            return jdbc.queryForList(
                    "SELECT * FROM vw_paquete_resumen WHERE tracking_code LIKE CONCAT('%', ?, '%') ORDER BY id DESC",
                    tracking);
        }
        return jdbc.queryForList(
                "SELECT * FROM vw_paquete_resumen WHERE tracking_code = ? ORDER BY id DESC",
                tracking);
    }

    @Cacheable(cacheNames = "busquedas", key = "'trk:'+ #patron")
    public List<Map<String, Object>> porTrackingLike(String patron) {
        return jdbc.queryForList("CALL sp_buscar_tracking_like(?)", patron);
    }

    @Cacheable(cacheNames = "inventario", key = "'fecha:'+ #tipoFecha + ':' + #desde + ':' + #hasta")
    public List<Map<String, Object>> porFecha(String tipoFecha, Instant desde, Instant hasta) {
        Timestamp pDesde = ts(desde);
        Timestamp pHasta = ts(hasta);
        String pTipo = (tipoFecha == null ? "CAMBIO" : tipoFecha);
        return jdbc.queryForList("CALL sp_paquetes_por_fecha(?, ?, ?)", pTipo, pDesde, pHasta);
    }

    @Cacheable(cacheNames = "inventario", key = "'mch:'+ #marchamo + ':' + #estado + ':' + #tipoFecha + ':' + #desde + ':' + #hasta")
    public List<Map<String, Object>> porMarchamo(String marchamo, String estado, String tipoFecha, Instant desde, Instant hasta) {
        Timestamp pDesde = ts(desde);
        Timestamp pHasta = ts(hasta);
        String pTipo = (tipoFecha == null ? "CAMBIO" : tipoFecha);
        return jdbc.queryForList("CALL sp_paquetes_por_marchamo(?, ?, ?, ?, ?)", marchamo, estado, pTipo, pDesde, pHasta);
    }

    /** Detalle simple por tracking */
    @Cacheable(cacheNames = "busquedas", key = "'det_simple:'+ #tracking")
    public List<Map<String, Object>> detallePorTracking(String tracking) {
        return jdbc.queryForList("CALL sp_tracking_distrito(?)", tracking);
    }

    /** Detalle + historial por tracking (SP con 2 result sets). */
    public Map<String, Object> detalleCompletoPorTracking(String tracking) {
        SimpleJdbcCall call = new SimpleJdbcCall(jdbc)
                .withProcedureName("sp_paquete_detalle_por_tracking");
        MapSqlParameterSource in = new MapSqlParameterSource().addValue("p_tracking", tracking);
        return call.execute(in);
    }

    /** Reporte diario (RAW: devuelve result sets de la SP). */
    public Map<String, Object> reporteDiario(LocalDate fecha) {
        SimpleJdbcCall call = new SimpleJdbcCall(jdbc)
                .withProcedureName("sp_reporte_diario");
        MapSqlParameterSource in = new MapSqlParameterSource().addValue("p_fecha", Date.valueOf(fecha));
        return call.execute(in);
    }

    @Cacheable(cacheNames = "inventario",
            key = "'distrito_filt:'+ #nombre + ':' + #tipoFecha + ':' + #desde + ':' + #hasta + ':' + #estado")
    public List<Map<String, Object>> porDistrito(String nombre,
                                                String tipoFecha,
                                                Instant desde,
                                                Instant hasta,
                                                String estado) {

        Timestamp pDesde = ts(desde);
        Timestamp pHasta = ts(hasta);

        String pTipo = (tipoFecha == null || tipoFecha.isBlank())
                ? "CAMBIO"
                : tipoFecha.trim().toUpperCase(Locale.ROOT);

        // ✅ aquí está el cambio: soportar CSV / EN_INVENTARIO / TODOS
        List<String> estados = parseEstados(estado);

        // Caso sin filtro de estado: llama la SP tal cual (p_estado = null)
        if (estados == null || estados.isEmpty()) {
            return jdbc.queryForList("CALL sp_paquetes_por_distrito(?, ?, ?, ?, ?)",
                    nombre, pTipo, pDesde, pHasta, null);
        }

        // Un solo estado: igual que antes
        if (estados.size() == 1) {
            return jdbc.queryForList("CALL sp_paquetes_por_distrito(?, ?, ?, ?, ?)",
                    nombre, pTipo, pDesde, pHasta, estados.get(0));
        }

        // ✅ Múltiples estados: NO le pasamos "A,B,C" a la SP (eso rompe).
        // Llamamos 1 vez por estado y unimos resultados.
        LinkedHashMap<Object, Map<String, Object>> uniq = new LinkedHashMap<>();

        for (String st : estados) {
            List<Map<String, Object>> part = jdbc.queryForList("CALL sp_paquetes_por_distrito(?, ?, ?, ?, ?)",
                    nombre, pTipo, pDesde, pHasta, st);

            for (Map<String, Object> row : part) {
                Object key = row.get("id");
                if (key == null) key = row.get("tracking_code");
                if (key == null) key = row; // fallback extremo
                // si ya existe, no lo pisa (primer match gana)
                uniq.putIfAbsent(key, row);
            }
        }

        List<Map<String, Object>> merged = new ArrayList<>(uniq.values());

        // Orden razonable (si viene id): DESC
        merged.sort((a, b) -> Long.compare(asLong(b.get("id")), asLong(a.get("id"))));

        return merged;
    }

    @Cacheable(cacheNames = "inventario", key = "'cnt_distrito:'+ #nombre")
    public long countPorDistrito(String nombre) {
        // alias para lo que ya tenés
        return countPorDistritoNombre(nombre);
    }

    /**
     * Reporte diario FLAT (para dashboard).
     * Regla rápida para evitar sobreconteo:
     * cada bloque cuenta SOLO paquetes cuyo estado actual corresponde al bloque.
     */
    public Map<String, Object> reporteDiarioFlat(LocalDate fecha) {
        if (fecha == null) return Map.of();

        Timestamp dIni = Timestamp.valueOf(fecha.atStartOfDay());
        Timestamp dFin = Timestamp.valueOf(fecha.plusDays(1).atStartOfDay());

        final String sql = """
            SELECT
              ? AS fecha,

              /* inventario al INICIO del día: solo estados que siguen en inventario */
              (SELECT COUNT(*)
                 FROM paquetes
                WHERE estado IN ('ENTREGADO_A_TRANSPORTISTA_LOCAL',
                                 'NO_ENTREGADO_CONSIGNATARIO_DISPONIBLE',
                                 'ENTREGADO_A_TRANSPORTISTA_LOCAL_2DO_INTENTO')
                  AND received_at < ?
              ) AS inventario,

              /* recibidos del día: solo paquetes cuyo estado actual sigue en inventario */
              (SELECT COUNT(*)
                 FROM paquetes
                WHERE estado IN ('ENTREGADO_A_TRANSPORTISTA_LOCAL',
                                 'NO_ENTREGADO_CONSIGNATARIO_DISPONIBLE',
                                 'ENTREGADO_A_TRANSPORTISTA_LOCAL_2DO_INTENTO')
                  AND received_at >= ? AND received_at < ?
              ) AS recibido,

              /* entregados del día: solo paquetes cuyo estado actual es POD */
              (SELECT COUNT(*)
                 FROM paquetes
                WHERE estado = 'PRUEBA_DE_ENTREGA'
                  AND delivered_at >= ? AND delivered_at < ?
              ) AS entregado,

              /* devoluciones del día: solo paquetes cuyo estado actual es NO_ENTREGABLE */
              (SELECT COUNT(*)
                 FROM paquetes
                WHERE estado = 'NO_ENTREGABLE'
                  AND returned_at >= ? AND returned_at < ?
              ) AS no_entregable,

              /* breakdown de devolución respetando el estado actual */
              (SELECT COUNT(*)
                 FROM paquetes
                WHERE estado = 'NO_ENTREGABLE'
                  AND returned_at >= ? AND returned_at < ?
                  AND devolucion_subtipo = 'FUERA_DE_RUTA'
              ) AS fuera_de_ruta,

              (SELECT COUNT(*)
                 FROM paquetes
                WHERE estado = 'NO_ENTREGABLE'
                  AND returned_at >= ? AND returned_at < ?
                  AND devolucion_subtipo = 'VENCIDOS'
              ) AS vencidos,

              (SELECT COUNT(*)
                 FROM paquetes
                WHERE estado = 'NO_ENTREGABLE'
                  AND returned_at >= ? AND returned_at < ?
                  AND devolucion_subtipo = 'DOS_INTENTOS'
              ) AS dos_intentos,

              /* inventario al CIERRE del día: solo estados actuales de inventario */
              (SELECT COUNT(*)
                 FROM paquetes
                WHERE estado IN ('ENTREGADO_A_TRANSPORTISTA_LOCAL',
                                 'NO_ENTREGADO_CONSIGNATARIO_DISPONIBLE',
                                 'ENTREGADO_A_TRANSPORTISTA_LOCAL_2DO_INTENTO')
                  AND received_at < ?
              ) AS total
            """;

        return jdbc.queryForMap(
            sql,
            fecha.toString(),

            // inventario inicio
            dIni,

            // recibido
            dIni, dFin,

            // entregado
            dIni, dFin,

            // no_entregable + subtipos
            dIni, dFin,
            dIni, dFin,
            dIni, dFin,
            dIni, dFin,

            // total cierre
            dFin
        );
    }
}