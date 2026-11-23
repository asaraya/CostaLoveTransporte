package com.cargosfsr.inventario.controllers;

import java.sql.Timestamp;
import com.cargosfsr.inventario.services.InventarioAlCierre;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

  private static final String ESTADO_RECIBIDO = "ENTREGADO_A_TRANSPORTISTA_LOCAL";
  private static final String ESTADO_NO_ENTREGADO = "NO_ENTREGADO_CONSIGNATARIO_DISPONIBLE";
  private static final String ESTADO_SEGUNDO_INTENTO = "ENTREGADO_A_TRANSPORTISTA_LOCAL_2DO_INTENTO";
  private static final String ESTADO_POD = "PRUEBA_DE_ENTREGA";
  private static final String ESTADO_NO_ENTREGABLE = "NO_ENTREGABLE";
  private static final String ESTADO_TR_A_CA = "TR_A_CA";

  private final JdbcTemplate jdbc;

  public DashboardController(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private int count(String sql, Object... args) {
    Long v = jdbc.queryForObject(sql, Long.class, args);
    return v == null ? 0 : v.intValue();
  }

  private Timestamp startOfDay(LocalDate fecha) {
    return Timestamp.valueOf(fecha.atStartOfDay());
  }

  private Timestamp startOfNextDay(LocalDate fecha) {
    return Timestamp.valueOf(fecha.plusDays(1).atStartOfDay());
  }

  private int safeLimit(int limit, int max) {
    return Math.max(1, Math.min(limit, max));
  }

  private int countReceivedBetween(Timestamp desde, Timestamp hasta) {
    return count(
        "CALL sp_th_recepciones_distintas(?, ?)",
        desde,
        hasta);
  }

  // La misma regla que Reportes: una fila por paquete y su estado vigente.
  // Un movimiento histórico revertido no es una entrega/devolución vigente.
  private int countDistinctEffectiveStateBetween(String estado, Timestamp desde, Timestamp hasta) {
    return count("CALL sp_th_estados_contar_dia(?, ?, ?)", estado, desde, hasta);
  }

  private int countDistinctEffectiveReturnSubtypeBetween(String subtipo, Timestamp desde, Timestamp hasta) {
    return count("CALL sp_th_devolucion_subtipo_contar(?, ?, ?)", subtipo, desde, hasta);
  }

  private Map<String, Integer> snapshotByEstado(LocalDate dia) {
    Map<String, Integer> out = new LinkedHashMap<>();
    out.put(ESTADO_RECIBIDO, 0);
    out.put(ESTADO_NO_ENTREGADO, 0);
    out.put(ESTADO_SEGUNDO_INTENTO, 0);
    out.put(ESTADO_POD, 0);
    out.put(ESTADO_NO_ENTREGABLE, 0);
    out.put(ESTADO_TR_A_CA, 0);
    out.put("NO_ENTREGABLE__FUERA_DE_RUTA", 0);
    out.put("NO_ENTREGABLE__VENCIDOS", 0);
    out.put("NO_ENTREGABLE__DOS_INTENTOS", 0);

    List<Map<String, Object>> rows = InventarioAlCierre.porEstado(jdbc, dia);

    for (Map<String, Object> row : rows) {
      String estado = String.valueOf(row.get("estado"));
      Number cantidad = (Number) row.get("cantidad");
      int qty = cantidad == null ? 0 : cantidad.intValue();
      String subtipo = row.get("devolucion_subtipo") == null ? null : String.valueOf(row.get("devolucion_subtipo"));

      out.put(estado, out.getOrDefault(estado, 0) + qty);

      if (ESTADO_NO_ENTREGABLE.equals(estado) && subtipo != null && !subtipo.isBlank()) {
        String key = "NO_ENTREGABLE__" + subtipo;
        out.put(key, out.getOrDefault(key, 0) + qty);
      }
    }

    return out;
  }

  private Map<String, Object> estadoRow(String estado, int cantidad) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("estado", estado);
    row.put("cantidad", cantidad);
    return row;
  }

  @GetMapping("/summary")
  public Map<String, Object> summary(@RequestParam(value = "fecha", required = false) String fecha) {
    LocalDate d = (fecha == null || fecha.isBlank()) ? LocalDate.now() : LocalDate.parse(fecha);
    Timestamp dIni = startOfDay(d);
    Timestamp dFinExcl = startOfNextDay(d);

    int totalPaquetesSistema = count("CALL sp_th_paquetes_distintos()");
    int recibidosFecha = countReceivedBetween(dIni, dFinExcl);
    int entregadosFecha = countDistinctEffectiveStateBetween(ESTADO_POD, dIni, dFinExcl);
    int noEntregableFecha = countDistinctEffectiveStateBetween(ESTADO_NO_ENTREGABLE, dIni, dFinExcl);
    int trACaFecha = countDistinctEffectiveStateBetween(ESTADO_TR_A_CA, dIni, dFinExcl);
    int noEntregableFueraDeRuta = countDistinctEffectiveReturnSubtypeBetween("FUERA_DE_RUTA", dIni, dFinExcl);
    int noEntregableVencidos = countDistinctEffectiveReturnSubtypeBetween("VENCIDOS", dIni, dFinExcl);
    int noEntregableDosIntentos = countDistinctEffectiveReturnSubtypeBetween("DOS_INTENTOS", dIni, dFinExcl);

    Map<String, Integer> snapshot = snapshotByEstado(d);
    int recibidosActual = snapshot.getOrDefault(ESTADO_RECIBIDO, 0);
    int noEntregadoDisponibleActual = snapshot.getOrDefault(ESTADO_NO_ENTREGADO, 0);
    int segundoIntentoActual = snapshot.getOrDefault(ESTADO_SEGUNDO_INTENTO, 0);
    int entregadosActual = snapshot.getOrDefault(ESTADO_POD, 0);
    int noEntregableActual = snapshot.getOrDefault(ESTADO_NO_ENTREGABLE, 0);
    int trACaActual = snapshot.getOrDefault(ESTADO_TR_A_CA, 0);
    int inventarioFecha = recibidosActual + noEntregadoDisponibleActual + segundoIntentoActual;

    int totalSacos = count("CALL sp_th_sacos_contar()");
    int sacosAbiertos = count("CALL sp_th_sacos_abiertos()");
    int sacosCerrados = count("CALL sp_th_sacos_cerrados()");

    List<Map<String, Object>> byEstado = new ArrayList<>();
    byEstado.add(estadoRow(ESTADO_RECIBIDO, recibidosActual));
    byEstado.add(estadoRow(ESTADO_NO_ENTREGADO, noEntregadoDisponibleActual));
    byEstado.add(estadoRow(ESTADO_SEGUNDO_INTENTO, segundoIntentoActual));
    byEstado.add(estadoRow(ESTADO_POD, entregadosActual));
    byEstado.add(estadoRow(ESTADO_NO_ENTREGABLE, noEntregableActual));
    byEstado.add(estadoRow(ESTADO_TR_A_CA, trACaActual));
    byEstado.add(estadoRow("NO_ENTREGABLE__FUERA_DE_RUTA", snapshot.getOrDefault("NO_ENTREGABLE__FUERA_DE_RUTA", 0)));
    byEstado.add(estadoRow("NO_ENTREGABLE__VENCIDOS", snapshot.getOrDefault("NO_ENTREGABLE__VENCIDOS", 0)));
    byEstado.add(estadoRow("NO_ENTREGABLE__DOS_INTENTOS", snapshot.getOrDefault("NO_ENTREGABLE__DOS_INTENTOS", 0)));

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("fecha", d.toString());

    Map<String, Object> totales = new LinkedHashMap<>();
    totales.put("paquetes", totalPaquetesSistema);
    totales.put("sacos", totalSacos);
    out.put("totales", totales);

    Map<String, Object> sacos = new LinkedHashMap<>();
    sacos.put("abiertos", sacosAbiertos);
    sacos.put("cerrados", sacosCerrados);
    out.put("sacos", sacos);

    Map<String, Object> hoy = new LinkedHashMap<>();
    hoy.put("recibidos", recibidosFecha);
    hoy.put("recibidos_disponible", noEntregadoDisponibleActual);
    hoy.put("segundo_intento", segundoIntentoActual);
    hoy.put("entregados", entregadosFecha);
    hoy.put("noEntregable", noEntregableFecha);
    hoy.put("no_entregable", noEntregableFecha);
    hoy.put("devoluciones", noEntregableFecha);
    hoy.put("fuera_de_ruta", noEntregableFueraDeRuta);
    hoy.put("vencidos", noEntregableVencidos);
    hoy.put("dos_intentos", noEntregableDosIntentos);
    hoy.put("tr_a_ca", trACaFecha);
    out.put("hoy", hoy);

    out.put("inventarioActual", inventarioFecha);
    out.put("inventarioFecha", inventarioFecha);
    out.put("byEstado", byEstado);
    return out;
  }

  @GetMapping("/top-ubicaciones")
  public List<Map<String, Object>> topUbicaciones(
      @RequestParam(value = "limit", defaultValue = "100") int limit,
      @RequestParam(value = "fecha", required = false) String fecha) {
    int lim = safeLimit(limit, 500);
    LocalDate d = (fecha == null || fecha.isBlank()) ? LocalDate.now() : LocalDate.parse(fecha);
    Timestamp cutoff = startOfNextDay(d);

    String sql = "CALL sp_th_cierre_ubicacion(?, ?, ?)";
    return jdbc.query(sql, (rs, i) -> {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("ubicacion", rs.getString("ubicacion"));
      m.put("cantidad", rs.getInt("cantidad"));
      return m;
    }, InventarioAlCierre.esHistorico(d), cutoff, lim);
  }

  @GetMapping("/top-distritos")
  public List<Map<String, Object>> topDistritos(
      @RequestParam(value = "limit", defaultValue = "100") int limit,
      @RequestParam(value = "fecha", required = false) String fecha) {
    int lim = safeLimit(limit, 500);
    LocalDate d = (fecha == null || fecha.isBlank()) ? LocalDate.now() : LocalDate.parse(fecha);
    Timestamp cutoff = startOfNextDay(d);

    String sql = "CALL sp_th_cierre_distrito(?, ?, ?)";
    return jdbc.query(sql, (rs, i) -> {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("distrito", rs.getString("distrito"));
      m.put("cantidad", rs.getInt("cantidad"));
      return m;
    }, InventarioAlCierre.esHistorico(d), cutoff, lim);
  }

  @GetMapping("/inventario-detalle")
  public List<Map<String, Object>> inventarioDetalle(@RequestParam LocalDate fecha,
      @RequestParam(required = false) String distrito, @RequestParam(required = false) String ubicacion) {
    boolean porDistrito = distrito != null;
    if (porDistrito == (ubicacion != null)) throw new org.springframework.web.server.ResponseStatusException(
        org.springframework.http.HttpStatus.BAD_REQUEST, "Seleccione distrito o ubicacion");
    return jdbc.queryForList("CALL sp_th_cierre_detalle(?, ?, ?, ?)", InventarioAlCierre.esHistorico(fecha),
        fecha.plusDays(1).atStartOfDay(), distrito, ubicacion);
  }

  @GetMapping("/top-transportistas")
  public List<Map<String, Object>> topTransportistas(
      @RequestParam(value = "limit", defaultValue = "100") int limit,
      @RequestParam(value = "fecha", required = false) String fecha) {
    int lim = safeLimit(limit, 500);

    boolean porDia = fecha != null && !fecha.isBlank();
    String sql = "CALL sp_th_mensajeros_totales(?, ?, ?)";
    if (!porDia) return jdbc.query(sql, (rs, i) -> mapTransportista(rs), null, null, lim);
    LocalDate dia = LocalDate.parse(fecha);
    return jdbc.query(sql, (rs, i) -> mapTransportista(rs), startOfDay(dia), startOfNextDay(dia), lim);
  }

  private Map<String, Object> mapTransportista(java.sql.ResultSet rs) throws java.sql.SQLException {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("mensajero_id", rs.getLong("mensajero_id"));
    m.put("transportista", rs.getString("transportista"));
    m.put("cantidad", rs.getInt("cantidad"));
    return m;
  }

  @GetMapping("/pods-transportista")
  public List<Map<String, Object>> podsPorTransportista(
      @RequestParam("mensajeroId") long mensajeroId,
      @RequestParam(value = "limit", defaultValue = "200") int limit,
      @RequestParam(value = "fecha", required = false) String fecha) {
    int lim = safeLimit(limit, 500);

    if (fecha == null || fecha.isBlank()) {
      String sql = "CALL sp_th_mensajero_pods(?, ?)";

      return jdbc.query(sql, (rs, i) -> mapPod(rs), mensajeroId, lim);
    }

    LocalDate d = LocalDate.parse(fecha);
    Timestamp dIni = startOfDay(d);
    Timestamp dFinExcl = startOfNextDay(d);

    String sql = "CALL sp_th_mensajero_pods_dia(?, ?, ?, ?)";
    return jdbc.query(sql, (rs, i) -> mapPod(rs), mensajeroId, dIni, dFinExcl, lim);
  }

  private Map<String, Object> mapPod(java.sql.ResultSet rs) throws java.sql.SQLException {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", rs.getLong("id"));
    m.put("tracking_code", rs.getString("tracking_code"));
    m.put("marchamo", rs.getString("marchamo"));
    m.put("distrito_nombre", rs.getString("distrito_nombre"));
    m.put("estado", rs.getString("estado"));
    m.put("delivered_at", rs.getTimestamp("delivered_at"));
    m.put("recipient_name", rs.getString("recipient_name"));
    m.put("recipient_phone", rs.getString("recipient_phone"));
    m.put("recipient_address", rs.getString("recipient_address"));
    return m;
  }

  @GetMapping("/ultimos-movimientos")
  public List<Map<String, Object>> ultimosMovimientos(
      @RequestParam(value = "limit", defaultValue = "20") int limit,
      @RequestParam(value = "fecha", required = false) String fecha) {
    int lim = safeLimit(limit, 200);

    String sql = "CALL sp_th_movimientos(?, ?, ?)";
    if (fecha == null || fecha.isBlank()) return jdbc.query(sql, (rs, i) -> mapMovimiento(rs), null, null, lim);
    LocalDate dia = LocalDate.parse(fecha);
    return jdbc.query(sql, (rs, i) -> mapMovimiento(rs), startOfDay(dia), startOfNextDay(dia), lim);
  }

  private Map<String, Object> mapMovimiento(java.sql.ResultSet rs) throws java.sql.SQLException {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("effective_at", rs.getTimestamp("effective_at"));
    m.put("hist_id", rs.getLong("hist_id"));
    m.put("tracking_code", rs.getString("tracking_code"));
    m.put("marchamo", rs.getString("marchamo"));
    m.put("distrito_nombre", rs.getString("distrito_nombre"));
    m.put("ubicacion_codigo", rs.getString("ubicacion_codigo"));
    m.put("estado_from", rs.getString("estado_from"));
    m.put("estado_to", rs.getString("estado_to"));
    m.put("changed_at", rs.getTimestamp("changed_at"));
    m.put("received_at", rs.getTimestamp("received_at"));
    m.put("delivered_at", rs.getTimestamp("delivered_at"));
    m.put("returned_at", rs.getTimestamp("returned_at"));
    m.put("motivo", rs.getString("motivo"));
    m.put("changed_by", rs.getString("changed_by"));
    return m;
  }
}
