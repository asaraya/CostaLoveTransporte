package com.cargosfsr.inventario.services;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
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

    private Timestamp ts(Instant i) { return i == null ? null : Timestamp.from(i); }

    /* ==========================
     * INVENTARIO PAGINADO (estado o TODOS)
     * default = NO_ENTREGADO_CONSIGNATARIO_DISPONIBLE
     * ========================== */
    public List<Map<String, Object>> inventarioPaginado(String estado, int limit, int offset) {
        int lim = Math.max(1, Math.min(limit, 1000));
        int off = Math.max(0, offset);

        String est = (estado == null || estado.isBlank())
                ? "NO_ENTREGADO_CONSIGNATARIO_DISPONIBLE"
                : estado.trim().toUpperCase();

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
        } else {
            final String sql = selectCols + " WHERE v.estado = ? ORDER BY v.id DESC LIMIT ? OFFSET ?";
            return jdbc.queryForList(sql, est, lim, off);
        }
    }

    /* ==========================
     * CONTADORES (para UI)
     * ========================== */

    @Cacheable(cacheNames = "inventario", key = "'cnt_inventario:'+ #estado")
    public long countInventario(String estado) {
        String est = (estado == null || estado.isBlank())
                ? "NO_ENTREGADO_CONSIGNATARIO_DISPONIBLE"
                : estado.trim().toUpperCase();

        if ("TODOS".equals(est)) {
            return jdbc.queryForObject("SELECT COUNT(*) FROM paquetes", Long.class);
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
        String pSubtipo  = (subtipo  == null || subtipo.isBlank())  ? "ALL" : subtipo.toUpperCase();
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
                : tipoFecha.trim().toUpperCase();

        String pEstado = (estado == null || estado.isBlank())
                ? null
                : estado.trim().toUpperCase();

        // SP: (p_distrito_nombre, p_tipo_fecha, p_desde, p_hasta, p_estado)
        return jdbc.queryForList("CALL sp_paquetes_por_distrito(?, ?, ?, ?, ?)",
                nombre, pTipo, pDesde, pHasta, pEstado);
    }

    @Cacheable(cacheNames = "inventario", key = "'cnt_distrito:'+ #nombre")
    public long countPorDistrito(String nombre) {
        // alias para lo que ya tenés
        return countPorDistritoNombre(nombre);
    }


    /**
     * Reporte diario FLAT (para dashboard).
     * Devuelve:
     * inventario, recibido, entregado, no_entregable, fuera_de_ruta, vencidos, dos_intentos, total
     */
    public Map<String, Object> reporteDiarioFlat(LocalDate fecha) {
        if (fecha == null) return Map.of();

        Timestamp dIni = Timestamp.valueOf(fecha.atStartOfDay());
        Timestamp dFin = Timestamp.valueOf(fecha.plusDays(1).atStartOfDay());

        final String sql = """
            SELECT
              ? AS fecha,

              /* inventario al INICIO del día (paquetes vigentes) */
              (SELECT COUNT(*)
                 FROM paquetes
                WHERE received_at < ?
                  AND (delivered_at IS NULL OR delivered_at >= ?)
                  AND (returned_at  IS NULL OR returned_at  >= ?)
              ) AS inventario,

              /* eventos del día */
              (SELECT COUNT(*) FROM paquetes WHERE received_at  >= ? AND received_at  < ?) AS recibido,
              (SELECT COUNT(*) FROM paquetes WHERE delivered_at >= ? AND delivered_at < ?) AS entregado,

              /* no entregable del día + breakdown por subtipo */
              (SELECT COUNT(*) FROM paquetes WHERE returned_at >= ? AND returned_at < ?) AS no_entregable,
              (SELECT COUNT(*) FROM paquetes WHERE returned_at >= ? AND returned_at < ? AND devolucion_subtipo = 'FUERA_DE_RUTA') AS fuera_de_ruta,
              (SELECT COUNT(*) FROM paquetes WHERE returned_at >= ? AND returned_at < ? AND devolucion_subtipo = 'VENCIDOS')      AS vencidos,
              (SELECT COUNT(*) FROM paquetes WHERE returned_at >= ? AND returned_at < ? AND devolucion_subtipo = 'DOS_INTENTOS')  AS dos_intentos,

              /* inventario al CIERRE del día */
              (SELECT COUNT(*)
                 FROM paquetes
                WHERE received_at < ?
                  AND (delivered_at IS NULL OR delivered_at >= ?)
                  AND (returned_at  IS NULL OR returned_at  >= ?)
              ) AS total
            """;

        return jdbc.queryForMap(
            sql,
            fecha.toString(),

            // inventario inicio
            dIni, dIni, dIni,

            // recibido, entregado
            dIni, dFin,
            dIni, dFin,

            // no_entregable + subtipos
            dIni, dFin,
            dIni, dFin,
            dIni, dFin,
            dIni, dFin,

            // total cierre
            dFin, dFin, dFin
        );
    }
}
