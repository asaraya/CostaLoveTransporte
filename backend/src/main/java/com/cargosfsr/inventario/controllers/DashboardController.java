package com.cargosfsr.inventario.controllers;

import java.time.LocalDate;
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

  private final JdbcTemplate jdbc;

  public DashboardController(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private int count(String sql, Object... args) {
    Long v = jdbc.queryForObject(sql, Long.class, args);
    return v == null ? 0 : v.intValue();
  }

  @GetMapping("/summary")
  public Map<String, Object> summary(@RequestParam(value = "fecha", required = false) String fecha) {
    LocalDate d = (fecha == null || fecha.isBlank()) ? LocalDate.now() : LocalDate.parse(fecha);
    String dIni = d + " 00:00:00";
    String dFinExcl = d.plusDays(1) + " 00:00:00";

    int totalPaquetes = count("SELECT COUNT(*) FROM paquetes");

    int inventarioActual =
        count(
            "SELECT COUNT(*) FROM paquetes WHERE estado IN ("
                + "'ENTREGADO_A_TRANSPORTISTA_LOCAL','NO_ENTREGADO_CONSIGNATARIO_DISPONIBLE','ENTREGADO_A_TRANSPORTISTA_LOCAL_2DO_INTENTO'"
                + ")");

    int recibidosHoy =
        count("SELECT COUNT(*) FROM paquetes WHERE received_at >= ? AND received_at < ?", dIni, dFinExcl);

    int entregadosHoy =
        count(
            "SELECT COUNT(*) FROM paquetes "
                + "WHERE received_at >= ? AND received_at < ? "
                + "AND estado = 'PRUEBA_DE_ENTREGA'",
            dIni,
            dFinExcl);

    int noEntregableHoy =
        count(
            "SELECT COUNT(*) FROM paquetes "
                + "WHERE received_at >= ? AND received_at < ? "
                + "AND estado = 'NO_ENTREGABLE'",
            dIni,
            dFinExcl);

    int recibidoTransportistaHoy =
        count(
            "SELECT COUNT(*) FROM paquetes "
                + "WHERE received_at >= ? AND received_at < ? "
                + "AND estado = 'ENTREGADO_A_TRANSPORTISTA_LOCAL'",
            dIni,
            dFinExcl);

    int noEntregadoConsignatarioHoy =
        count(
            "SELECT COUNT(*) FROM paquetes "
                + "WHERE received_at >= ? AND received_at < ? "
                + "AND estado = 'NO_ENTREGADO_CONSIGNATARIO_DISPONIBLE'",
            dIni,
            dFinExcl);

    int segundoIntentoHoy =
        count(
            "SELECT COUNT(*) FROM paquetes "
                + "WHERE received_at >= ? AND received_at < ? "
                + "AND estado = 'ENTREGADO_A_TRANSPORTISTA_LOCAL_2DO_INTENTO'",
            dIni,
            dFinExcl);

    int inventarioActualHoy =
        count(
            "SELECT COUNT(*) FROM paquetes "
                + "WHERE received_at >= ? AND received_at < ? "
                + "AND estado IN ("
                + "'ENTREGADO_A_TRANSPORTISTA_LOCAL',"
                + "'NO_ENTREGADO_CONSIGNATARIO_DISPONIBLE',"
                + "'ENTREGADO_A_TRANSPORTISTA_LOCAL_2DO_INTENTO'"
                + ")",
            dIni,
            dFinExcl);

    int totalSacos = count("SELECT COUNT(*) FROM sacos");
    int sacosAbiertos = count("SELECT COUNT(*) FROM sacos WHERE closed_at IS NULL");
    int sacosCerrados = count("SELECT COUNT(*) FROM sacos WHERE closed_at IS NOT NULL");

    List<Map<String, Object>> byEstado =
        jdbc.query(
            "SELECT estado, COUNT(*) AS cantidad FROM paquetes GROUP BY estado",
            (rs, i) -> {
              Map<String, Object> m = new LinkedHashMap<>();
              m.put("estado", rs.getString("estado"));
              m.put("cantidad", rs.getInt("cantidad"));
              return m;
            });

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("fecha", d.toString());

    Map<String, Object> totales = new LinkedHashMap<>();
    totales.put("paquetes", totalPaquetes);
    totales.put("sacos", totalSacos);
    out.put("totales", totales);

    Map<String, Object> sacos = new LinkedHashMap<>();
    sacos.put("abiertos", sacosAbiertos);
    sacos.put("cerrados", sacosCerrados);
    out.put("sacos", sacos);

    Map<String, Object> hoy = new LinkedHashMap<>();
    hoy.put("recibidos", recibidosHoy);
    hoy.put("inventario", inventarioActualHoy);
    hoy.put("recibido_transportista", recibidoTransportistaHoy);
    hoy.put("no_entregado", noEntregadoConsignatarioHoy);
    hoy.put("segundo_intento", segundoIntentoHoy);
    hoy.put("entregados", entregadosHoy);
    hoy.put("no_entregable", noEntregableHoy);
    out.put("hoy", hoy);

    out.put("inventarioActual", inventarioActual);
    out.put("byEstado", byEstado);
    return out;
  }

  @GetMapping("/top-distritos")
  public List<Map<String, Object>> topDistritos(@RequestParam(value = "limit", defaultValue = "10") int limit) {
    String sql =
        """
        SELECT v.distrito_nombre AS distrito, COUNT(*) AS cantidad
        FROM vw_paquete_resumen v
        WHERE v.estado IN (
          'ENTREGADO_A_TRANSPORTISTA_LOCAL',
          'NO_ENTREGADO_CONSIGNATARIO_DISPONIBLE',
          'ENTREGADO_A_TRANSPORTISTA_LOCAL_2DO_INTENTO'
        )
        GROUP BY v.distrito_nombre
        ORDER BY cantidad DESC, distrito ASC
        LIMIT ?
        """;
    return jdbc.query(
        sql,
        (rs, i) -> {
          Map<String, Object> m = new LinkedHashMap<>();
          m.put("distrito", rs.getString("distrito"));
          m.put("cantidad", rs.getInt("cantidad"));
          return m;
        },
        limit);
  }

  @GetMapping("/top-transportistas")
  public List<Map<String, Object>> topTransportistas(
      @RequestParam(value = "limit", defaultValue = "10") int limit,
      @RequestParam(value = "fecha", required = false) String fecha) {

    String dIni = null;
    String dFinExcl = null;
    String marker = null;

    if (fecha != null && !fecha.isBlank()) {
      LocalDate d = LocalDate.parse(fecha);
      dIni = d + " 00:00:00";
      dFinExcl = d.plusDays(1) + " 00:00:00";
      marker = dIni;
    }

    String sql =
        """
        SELECT u.id AS mensajero_id,
               u.full_name AS transportista,
               COALESCE(COUNT(p.id), 0) AS cantidad
        FROM usuarios u
        LEFT JOIN paquetes p
          ON p.mensajero_id = u.id
         AND p.estado = 'PRUEBA_DE_ENTREGA'
         AND (? IS NULL OR (p.received_at >= ? AND p.received_at < ?))
        WHERE u.rol = 'MENSAJERO'
        GROUP BY u.id, u.full_name
        ORDER BY cantidad DESC, transportista ASC
        LIMIT ?
        """;

    return jdbc.query(
        sql,
        (rs, i) -> {
          Map<String, Object> m = new LinkedHashMap<>();
          m.put("mensajero_id", rs.getLong("mensajero_id"));
          m.put("transportista", rs.getString("transportista"));
          m.put("cantidad", rs.getInt("cantidad"));
          return m;
        },
        marker,
        dIni,
        dFinExcl,
        limit);
  }

  @GetMapping("/pods-transportista")
  public List<Map<String, Object>> podsPorTransportista(
      @RequestParam("mensajeroId") long mensajeroId,
      @RequestParam(value = "limit", defaultValue = "100000") int limit,
      @RequestParam(value = "fecha", required = false) String fecha) {

    String dIni = null;
    String dFinExcl = null;
    String marker = null;

    if (fecha != null && !fecha.isBlank()) {
      LocalDate d = LocalDate.parse(fecha);
      dIni = d + " 00:00:00";
      dFinExcl = d.plusDays(1) + " 00:00:00";
      marker = dIni;
    }

    String sql =
        """
        SELECT p.id,
               p.tracking_code,
               s.marchamo,
               d.nombre AS distrito_nombre,
               p.estado,
               p.delivered_at,
               p.received_at,
               p.recipient_name,
               p.recipient_phone,
               p.recipient_address
        FROM paquetes p
        JOIN sacos s ON s.id = p.saco_id
        JOIN distritos d ON d.id = p.distrito_id
        WHERE p.estado = 'PRUEBA_DE_ENTREGA'
          AND p.mensajero_id = ?
          AND (? IS NULL OR (p.received_at >= ? AND p.received_at < ?))
        ORDER BY p.delivered_at DESC, p.id DESC
        LIMIT ?
        """;

    return jdbc.query(
        sql,
        (rs, i) -> {
          Map<String, Object> m = new LinkedHashMap<>();
          m.put("id", rs.getLong("id"));
          m.put("tracking_code", rs.getString("tracking_code"));
          m.put("marchamo", rs.getString("marchamo"));
          m.put("distrito_nombre", rs.getString("distrito_nombre"));
          m.put("estado", rs.getString("estado"));
          m.put("delivered_at", rs.getTimestamp("delivered_at"));
          m.put("received_at", rs.getTimestamp("received_at"));
          m.put("recipient_name", rs.getString("recipient_name"));
          m.put("recipient_phone", rs.getString("recipient_phone"));
          m.put("recipient_address", rs.getString("recipient_address"));
          return m;
        },
        mensajeroId,
        marker,
        dIni,
        dFinExcl,
        limit);
  }

  @GetMapping("/ultimos-movimientos")
  public List<Map<String, Object>> ultimosMovimientos(
      @RequestParam(value = "limit", defaultValue = "20") int limit,
      @RequestParam(value = "fecha", required = false) String fecha) {

    String base =
        """
        SELECT h.id AS hist_id, p.tracking_code, v.marchamo, v.distrito_nombre,
               h.estado_from, h.estado_to, h.changed_at, p.received_at, p.delivered_at, p.returned_at,
               h.motivo, h.changed_by
        FROM paquete_estado_historial h
        JOIN paquetes p ON p.id = h.paquete_id
        JOIN vw_paquete_resumen v ON v.id = p.id
        """;

    String orderLimit = " ORDER BY h.changed_at DESC, h.id DESC LIMIT ? ";

    if (fecha == null || fecha.isBlank()) {
      String sql = base + "\n" + orderLimit;
      return jdbc.query(
          sql,
          (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("hist_id", rs.getLong("hist_id"));
            m.put("tracking_code", rs.getString("tracking_code"));
            m.put("marchamo", rs.getString("marchamo"));
            m.put("distrito_nombre", rs.getString("distrito_nombre"));
            m.put("estado_from", rs.getString("estado_from"));
            m.put("estado_to", rs.getString("estado_to"));
            m.put("changed_at", rs.getTimestamp("changed_at"));
            m.put("received_at", rs.getTimestamp("received_at"));
            m.put("delivered_at", rs.getTimestamp("delivered_at"));
            m.put("returned_at", rs.getTimestamp("returned_at"));
            m.put("motivo", rs.getString("motivo"));
            m.put("changed_by", rs.getString("changed_by"));
            return m;
          },
          limit);
    }

    LocalDate d = LocalDate.parse(fecha);
    String dIni = d + " 00:00:00";
    String dFinExcl = d.plusDays(1) + " 00:00:00";

    String sql =
        base
            + """
            WHERE CASE
              WHEN h.estado_to = 'PRUEBA_DE_ENTREGA' AND p.delivered_at IS NOT NULL THEN p.delivered_at
              WHEN h.estado_to = 'NO_ENTREGABLE' AND p.returned_at IS NOT NULL THEN p.returned_at
              ELSE h.changed_at
            END >= ?
            AND CASE
              WHEN h.estado_to = 'PRUEBA_DE_ENTREGA' AND p.delivered_at IS NOT NULL THEN p.delivered_at
              WHEN h.estado_to = 'NO_ENTREGABLE' AND p.returned_at IS NOT NULL THEN p.returned_at
              ELSE h.changed_at
            END < ?
            """
            + "\n"
            + orderLimit;

    return jdbc.query(
        sql,
        (rs, i) -> {
          Map<String, Object> m = new LinkedHashMap<>();
          m.put("hist_id", rs.getLong("hist_id"));
          m.put("tracking_code", rs.getString("tracking_code"));
          m.put("marchamo", rs.getString("marchamo"));
          m.put("distrito_nombre", rs.getString("distrito_nombre"));
          m.put("estado_from", rs.getString("estado_from"));
          m.put("estado_to", rs.getString("estado_to"));
          m.put("changed_at", rs.getTimestamp("changed_at"));
          m.put("received_at", rs.getTimestamp("received_at"));
          m.put("delivered_at", rs.getTimestamp("delivered_at"));
          m.put("returned_at", rs.getTimestamp("returned_at"));
          m.put("motivo", rs.getString("motivo"));
          m.put("changed_by", rs.getString("changed_by"));
          return m;
        },
        dIni,
        dFinExcl,
        limit);
  }
}