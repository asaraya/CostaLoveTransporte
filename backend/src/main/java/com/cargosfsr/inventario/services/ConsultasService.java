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

    private static final String ESTADO_RECIBIDO = "ENTREGADO_A_TRANSPORTISTA_LOCAL";
    private static final String ESTADO_NO_ENTREGADO = "NO_ENTREGADO_CONSIGNATARIO_DISPONIBLE";
    private static final String ESTADO_SEGUNDO_INTENTO = "ENTREGADO_A_TRANSPORTISTA_LOCAL_2DO_INTENTO";
    private static final String ESTADO_POD = "PRUEBA_DE_ENTREGA";
    private static final String ESTADO_NO_ENTREGABLE = "NO_ENTREGABLE";
    private static final String ESTADO_TR_A_CA = "TR_A_CA";

    private final JdbcTemplate jdbc;

    private List<Map<String, Object>> queryPaquetes(String sql, Object... args) {
        return FechasPaquete.completar(jdbc, jdbc.queryForList(sql, args));
    }

    public ConsultasService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Regla de negocio:
     * - EN_INVENTARIO = cualquier paquete cuyo estado esté en cualquiera de estos 3 estados.
     * - NO_ENTREGABLE (cualquier subtipo) = fuera de inventario.
     */
    private static final List<String> ESTADOS_EN_INVENTARIO = List.of(
            ESTADO_RECIBIDO,
            ESTADO_NO_ENTREGADO,
            ESTADO_SEGUNDO_INTENTO
    );

    private Timestamp ts(Instant i) { return i == null ? null : Timestamp.from(i); }

    private long countLong(String sql, Object... args) {
        Long v = jdbc.queryForObject(sql, Long.class, args);
        return v == null ? 0L : v;
    }

    private long countReceivedBetween(Timestamp desde, Timestamp hasta) {
        return countLong(
                "CALL sp_th_recibido_contar(?, ?)",
                desde,
                hasta);
    }

    private long countDistinctEffectiveStateBetween(String estado, Timestamp desde, Timestamp hasta) {
        return countLong("CALL sp_th_estados_contar_dia(?, ?, ?)", estado, desde, hasta);
    }

    private long countDistinctEffectiveReturnSubtypeBetween(String subtipo, Timestamp desde, Timestamp hasta) {
        return countLong(
                "CALL sp_th_devolucion_subtipo_contar(?, ?, ?)",
                subtipo,
                desde,
                hasta);
    }

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
                ? ESTADO_NO_ENTREGADO
                : estado.trim().toUpperCase(Locale.ROOT);

        return queryPaquetes("CALL sp_th_inventario_listar(?, ?, ?)", est, lim, off);
    }

    /* ==========================
     * CONTADORES (para UI)
     * ========================== */

    @Cacheable(cacheNames = "inventario", key = "'cnt_inventario:'+ #estado")
    public long countInventario(String estado) {
        String est = (estado == null || estado.isBlank())
                ? ESTADO_NO_ENTREGADO
                : estado.trim().toUpperCase(Locale.ROOT);

        return jdbc.queryForObject("CALL sp_th_inventario_contar(?)", Long.class, est);
    }

    @Cacheable(cacheNames = "inventario", key = "'cnt_marchamo:'+ #marchamo")
    public long countPorMarchamo(String marchamo) {
        return jdbc.queryForObject("CALL sp_th_contar_marchamo(?)", Long.class, marchamo);
    }

    @Cacheable(cacheNames = "inventario", key = "'cnt_distrito:'+ #nombre")
    public long countPorDistritoNombre(String nombre) {
        return jdbc.queryForObject("CALL sp_th_contar_distrito(?)",
                Long.class, nombre);
    }

    @Cacheable(cacheNames = "inventario", key = "'cnt_ubicacion:'+ #codigo")
    public long countPorUbicacionCodigo(String codigo) {
        return jdbc.queryForObject("CALL sp_th_contar_ubicacion(?)",
                Long.class, codigo);
    }

    @Cacheable(cacheNames = "busquedas", key = "'cnt_tracking:'+ #q +':'+ #like")
    public long countPorTracking(String q, int like) {
        if (like == 1) {
            return jdbc.queryForObject(
                    "CALL sp_th_contar_tracking_parcial(?)",
                    Long.class, q);
        }
        return jdbc.queryForObject("CALL sp_th_contar_tracking_exacto(?)",
                Long.class, q);
    }

    @Cacheable(cacheNames = "busquedas", key = "'cnt_nombre:'+ #q +':'+ #like")
    public long countPorNombre(String q, int like) {
        if (like == 1) {
            return jdbc.queryForObject(
                    "CALL sp_th_contar_nombre_parcial(?)",
                    Long.class, q);
        }
        return jdbc.queryForObject("CALL sp_th_contar_nombre_exacto(?)",
                Long.class, q);
    }

    @Cacheable(cacheNames = "busquedas", key = "'cnt_direccion:'+ #q +':'+ #like")
    public long countPorDireccion(String q, int like) {
        if (like == 1) {
            return jdbc.queryForObject(
                    "CALL sp_th_contar_direccion_parcial(?)",
                    Long.class, q);
        }
        return jdbc.queryForObject("CALL sp_th_contar_direccion_exacta(?)",
                Long.class, q);
    }

    /* ==========================
     * REPORTES (ENTREGADOS / NO ENTREGABLE)
     * ========================== */

    public List<Map<String, Object>> entregados(Instant desde, Instant hasta, String marchamo) {
        Timestamp pDesde = ts(desde);
        Timestamp pHasta = ts(hasta);
        String pMarchamo = (marchamo == null || marchamo.isBlank()) ? null : marchamo;
        return queryPaquetes("CALL sp_paquetes_entregados(?, ?, ?)", pDesde, pHasta, pMarchamo);
    }

    public List<Map<String, Object>> devolucion(Instant desde, Instant hasta, String marchamo, String subtipo) {
        Timestamp pDesde = ts(desde);
        Timestamp pHasta = ts(hasta);
        String pMarchamo = (marchamo == null || marchamo.isBlank()) ? null : marchamo;
        String pSubtipo  = (subtipo  == null || subtipo.isBlank())  ? "ALL" : subtipo.toUpperCase(Locale.ROOT);
        return queryPaquetes("CALL sp_paquetes_devolucion(?, ?, ?, ?)", pDesde, pHasta, pMarchamo, pSubtipo);
    }

    /* ==========================
     * BÚSQUEDAS / FILTROS
     * ========================== */

    @Cacheable(cacheNames = "inventario", key = "'estado:'+ #estado + ':' + #tipoFecha + ':' + #desde + ':' + #hasta")
    public List<Map<String, Object>> porEstado(String estado, String tipoFecha, Instant desde, Instant hasta) {
        Timestamp pDesde = ts(desde);
        Timestamp pHasta = ts(hasta);
        String pTipo = (tipoFecha == null ? "CAMBIO" : tipoFecha);
        return queryPaquetes("CALL sp_paquetes_por_estado(?, ?, ?, ?)", estado, pTipo, pDesde, pHasta);
    }

    @Cacheable(cacheNames = "inventario", key = "'distrito:'+ #nombre")
    public List<Map<String, Object>> porDistritoNombre(String nombre) {
        // SP: (p_distrito_nombre, p_tipo_fecha, p_desde, p_hasta, p_estado)
        return queryPaquetes("CALL sp_paquetes_por_distrito(?, ?, ?, ?, ?)", nombre, null, null, null, null);
    }

    @Cacheable(cacheNames = "inventario", key = "'ubic:'+ #codigo")
    public List<Map<String, Object>> porUbicacionCodigo(String codigo) {
        return queryPaquetes("CALL sp_paquetes_por_ubicacion(?, ?, ?, ?, ?)", codigo, null, null, null, null);
    }

    @Cacheable(cacheNames = "busquedas", key = "'nom_exact_like:'+ #nombre +':'+ #like")
    public List<Map<String, Object>> porNombre(String nombre, int like) {
        int pLike = like == 0 ? 0 : 1;
        if (pLike == 1) {
            return queryPaquetes(
                    "CALL sp_th_buscar_nombre_parcial(?)",
                    nombre);
        }
        return queryPaquetes(
                "CALL sp_th_buscar_nombre_exacto(?)",
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
        return queryPaquetes("CALL sp_paquetes_por_direccion(?, ?)", dir, pLike);
    }

    @Cacheable(cacheNames = "busquedas", key = "'dir:'+ #dir")
    public List<Map<String, Object>> porDireccionContiene(String dir) {
        return porDireccion(dir, 1);
    }

    @Cacheable(cacheNames = "busquedas", key = "'trk_exact_like:'+ #tracking +':'+ #like")
    public List<Map<String, Object>> porTracking(String tracking, int like) {
        int pLike = like == 0 ? 0 : 1;
        if (pLike == 1) {
            return queryPaquetes(
                    "CALL sp_th_buscar_tracking_parcial(?)",
                    tracking);
        }
        return queryPaquetes(
                "CALL sp_th_buscar_tracking_exacto(?)",
                tracking);
    }

    @Cacheable(cacheNames = "busquedas", key = "'trk:'+ #patron")
    public List<Map<String, Object>> porTrackingLike(String patron) {
        return queryPaquetes("CALL sp_buscar_tracking_like(?)", patron);
    }

    @Cacheable(cacheNames = "inventario", key = "'fecha:'+ #tipoFecha + ':' + #desde + ':' + #hasta")
    public List<Map<String, Object>> porFecha(String tipoFecha, Instant desde, Instant hasta) {
        Timestamp pDesde = ts(desde);
        Timestamp pHasta = ts(hasta);
        String pTipo = (tipoFecha == null ? "CAMBIO" : tipoFecha);
        return queryPaquetes("CALL sp_paquetes_por_fecha(?, ?, ?)", pTipo, pDesde, pHasta);
    }

    @Cacheable(cacheNames = "inventario", key = "'mch:'+ #marchamo + ':' + #estado + ':' + #tipoFecha + ':' + #desde + ':' + #hasta")
    public List<Map<String, Object>> porMarchamo(String marchamo, String estado, String tipoFecha, Instant desde, Instant hasta) {
        Timestamp pDesde = ts(desde);
        Timestamp pHasta = ts(hasta);
        String pTipo = (tipoFecha == null ? "CAMBIO" : tipoFecha);
        return queryPaquetes("CALL sp_paquetes_por_marchamo(?, ?, ?, ?, ?)", marchamo, estado, pTipo, pDesde, pHasta);
    }

    /** Detalle simple por tracking */
    @Cacheable(cacheNames = "busquedas", key = "'det_simple:'+ #tracking")
    public List<Map<String, Object>> detallePorTracking(String tracking) {
        return queryPaquetes("CALL sp_tracking_distrito(?)", tracking);
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
            return queryPaquetes("CALL sp_paquetes_por_distrito(?, ?, ?, ?, ?)",
                    nombre, pTipo, pDesde, pHasta, null);
        }

        // Un solo estado: igual que antes
        if (estados.size() == 1) {
            return queryPaquetes("CALL sp_paquetes_por_distrito(?, ?, ?, ?, ?)",
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

        return FechasPaquete.completar(jdbc, merged);
    }

    @Cacheable(cacheNames = "inventario",
            key = "'ubic_filt:'+ #codigo + ':' + #tipoFecha + ':' + #desde + ':' + #hasta + ':' + #estado")
    public List<Map<String, Object>> porUbicacion(String codigo,
                                                String tipoFecha,
                                                Instant desde,
                                                Instant hasta,
                                                String estado) {
        Timestamp pDesde = ts(desde);
        Timestamp pHasta = ts(hasta);
        String pTipo = (tipoFecha == null || tipoFecha.isBlank())
                ? "CAMBIO"
                : tipoFecha.trim().toUpperCase(Locale.ROOT);
        List<String> estados = parseEstados(estado);

        if (estados == null || estados.isEmpty()) {
            return queryPaquetes("CALL sp_paquetes_por_ubicacion(?, ?, ?, ?, ?)",
                    codigo, pTipo, pDesde, pHasta, null);
        }
        if (estados.size() == 1) {
            return queryPaquetes("CALL sp_paquetes_por_ubicacion(?, ?, ?, ?, ?)",
                    codigo, pTipo, pDesde, pHasta, estados.get(0));
        }

        LinkedHashMap<Object, Map<String, Object>> uniq = new LinkedHashMap<>();
        for (String st : estados) {
            List<Map<String, Object>> part = jdbc.queryForList("CALL sp_paquetes_por_ubicacion(?, ?, ?, ?, ?)",
                    codigo, pTipo, pDesde, pHasta, st);
            for (Map<String, Object> row : part) {
                Object key = row.get("id");
                if (key == null) key = row.get("tracking_code");
                if (key == null) key = row;
                uniq.putIfAbsent(key, row);
            }
        }
        List<Map<String, Object>> merged = new ArrayList<>(uniq.values());
        merged.sort((a, b) -> Long.compare(asLong(b.get("id")), asLong(a.get("id"))));
        return FechasPaquete.completar(jdbc, merged);
    }

    @Cacheable(cacheNames = "inventario", key = "'cnt_distrito:'+ #nombre")
    public long countPorDistrito(String nombre) {
        // alias para lo que ya tenés
        return countPorDistritoNombre(nombre);
    }

    /** Reporte diario FLAT usado por el Dashboard: inventario/total corresponden al cierre del día. */
    public Map<String, Object> reporteDiarioFlat(LocalDate fecha) {
        if (fecha == null) return Map.of();

        Timestamp dIni = Timestamp.valueOf(fecha.atStartOfDay());
        Timestamp dFin = Timestamp.valueOf(fecha.plusDays(1).atStartOfDay());

        long inventarioAlCierre = InventarioAlCierre.porEstado(jdbc, fecha).stream()
                .filter(row -> ESTADOS_EN_INVENTARIO.contains(row.get("estado")))
                .mapToLong(row -> ((Number)row.get("cantidad")).longValue()).sum();

        long recibido = countReceivedBetween(dIni, dFin);
        long entregado = countDistinctEffectiveStateBetween(ESTADO_POD, dIni, dFin);
        long noEntregable = countDistinctEffectiveStateBetween(ESTADO_NO_ENTREGABLE, dIni, dFin);
        long trACa = countDistinctEffectiveStateBetween(ESTADO_TR_A_CA, dIni, dFin);
        long fueraDeRuta = countDistinctEffectiveReturnSubtypeBetween("FUERA_DE_RUTA", dIni, dFin);
        long vencidos = countDistinctEffectiveReturnSubtypeBetween("VENCIDOS", dIni, dFin);
        long dosIntentos = countDistinctEffectiveReturnSubtypeBetween("DOS_INTENTOS", dIni, dFin);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("fecha", fecha.toString());
        out.put("inventario", inventarioAlCierre);
        out.put("recibido", recibido);
        out.put("entregado", entregado);
        out.put("no_entregable", noEntregable);
        out.put("fuera_de_ruta", fueraDeRuta);
        out.put("vencidos", vencidos);
        out.put("dos_intentos", dosIntentos);
        out.put("tr_a_ca", trACa);
        out.put("total", inventarioAlCierre);
        return out;
    }
}