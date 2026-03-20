package com.cargosfsr.inventario.controllers;

import java.sql.Timestamp;
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

  private int countReceivedBetween(Timestamp desde, Timestamp hasta) {
    return count(
        """
        SELECT COUNT(*)
        FROM paquetes p
        WHERE p.received_at >= ?
          AND p.received_at < ?
        """,
        desde,
        hasta);
  }

  private int countDistinctEffectiveStateBetween(String estado, Timestamp desde, Timestamp hasta) {
    return count(
        """
        SELECT COUNT(DISTINCT x.paquete_id)
        FROM (
          SELECT
            h.paquete_id,
            h.estado_to,
            CASE
              WHEN h.estado_to = 'PRUEBA_DE_ENTREGA' AND p.delivered_at IS NOT NULL THEN p.delivered_at
              WHEN h.estado_to = 'NO_ENTREGABLE' AND p.returned_at IS NOT NULL THEN p.returned_at
              WHEN h.estado_from IS NULL AND p.received_at IS NOT NULL THEN p.received_at
              ELSE h.changed_at
            END AS effective_at
          FROM paquete_estado_historial h
          JOIN paquetes p ON p.id = h.paquete_id
        ) x
        WHERE x.estado_to = ?
          AND x.effective_at >= ?
          AND x.effective_at < ?
        """,
        estado,
        desde,
        hasta);
  }

  private int countDistinctEffectiveReturnSubtypeBetween(
      String subtipo, Timestamp desde, Timestamp hasta) {
    return count(
        """
        SELECT COUNT(DISTINCT x.paquete_id)
        FROM (
          SELECT
            h.paquete_id,
            h.estado_to,
            p.devolucion_subtipo,
            CASE
              WHEN h.estado_to = 'PRUEBA_DE_ENTREGA' AND p.delivered_at IS NOT NULL THEN p.delivered_at
              WHEN h.estado_to = 'NO_ENTREGABLE' AND p.returned_at IS NOT NULL THEN p.returned_at
              WHEN h.estado_from IS NULL AND p.received_at IS NOT NULL THEN p.received_at
              ELSE h.changed_at
            END AS effective_at
          FROM paquete_estado_historial h
          JOIN paquetes p ON p.id = h.paquete_id
        ) x
        WHERE x.estado_to = 'NO_ENTREGABLE'
          AND x.devolucion_subtipo = ?
          AND x.effective_at >= ?
          AND x.effective_at < ?
        """,
        subtipo,
        desde,
        hasta);
  }

  private Map<String, Integer> snapshotByEstado(Timestamp cutoffExclusivo) {
    Map<String, Integer> out = new LinkedHashMap<>();
    out.put(ESTADO_RECIBIDO, 0);
    out.put(ESTADO_NO_ENTREGADO, 0);
    out.put(ESTADO_SEGUNDO_INTENTO, 0);
    out.put(ESTADO_POD, 0);
    out.put(ESTADO_NO_ENTREGABLE, 0);
    out.put("NO_ENTREGABLE__FUERA_DE_RUTA", 0);
    out.put("NO_ENTREGABLE__VENCIDOS", 0);
    out.put("NO_ENTREGABLE__DOS_INTENTOS", 0);

    List<Map<String, Object>> rows =
        jdbc.queryForList(
            """
            WITH ranked AS (
              SELECT
                x.paquete_id,
                x.estado_to,
                x.devolucion_subtipo,
                ROW_NUMBER() OVER (
                  PARTITION BY x.paquete_id
                  ORDER BY x.effective_at DESC, x.id DESC
                ) AS rn
              FROM (
                SELECT
                  h.id,
                  h.paquete_id,
                  h.estado_to,
                  p.devolucion_subtipo,
                  CASE
                    WHEN h.estado_to = 'PRUEBA_DE_ENTREGA' AND p.delivered_at IS NOT NULL THEN p.delivered_at
                    WHEN h.estado_to = 'NO_ENTREGABLE' AND p.returned_at IS NOT NULL THEN p.returned_at
                    WHEN h.estado_from IS NULL AND p.received_at IS NOT NULL THEN p.received_at
                    ELSE h.changed_at
                  END AS effective_at
                FROM paquete_estado_historial h
                JOIN paquetes p ON p.id = h.paquete_id
              ) x
              WHERE x.effective_at < ?
            )
            SELECT
              estado_to AS estado,
              devolucion_subtipo,
              COUNT(*) AS cantidad
            FROM ranked
            WHERE rn = 1
            GROUP BY estado_to, devolucion_subtipo
            """,
            cutoffExclusivo);

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

    int totalPaquetesSistema = count("SELECT COUNT(*) FROM paquetes");
    int recibidosFecha = countReceivedBetween(dIni, dFinExcl);
    int entregadosFecha = countDistinctEffectiveStateBetween(ESTADO_POD, dIni, dFinExcl);
    int noEntregableFecha = countDistinctEffectiveStateBetween(ESTADO_NO_ENTREGABLE, dIni, dFinExcl);
    int noEntregableFueraDeRuta =
        countDistinctEffectiveReturnSubtypeBetween("FUERA_DE_RUTA", dIni, dFinExcl);
    int noEntregableVencidos = countDistinctEffectiveReturnSubtypeBetween("VENCIDOS", dIni, dFinExcl);
    int noEntregableDosIntentos =
        countDistinctEffectiveReturnSubtypeBetween("DOS_INTENTOS", dIni, dFinExcl);

    Map<String, Integer> snapshot = snapshotByEstado(dFinExcl);
    int recibidosActual = snapshot.getOrDefault(ESTADO_RECIBIDO, 0);
    int noEntregadoDisponibleActual = snapshot.getOrDefault(ESTADO_NO_ENTREGADO, 0);
    int segundoIntentoActual = snapshot.getOrDefault(ESTADO_SEGUNDO_INTENTO, 0);
    int entregadosActual = snapshot.getOrDefault(ESTADO_POD, 0);
    int noEntregableActual = snapshot.getOrDefault(ESTADO_NO_ENTREGABLE, 0);
    int inventarioActual = recibidosActual + noEntregadoDisponibleActual + segundoIntentoActual;

    int totalSacos = count("SELECT COUNT(*) FROM sacos");
    int sacosAbiertos = count("SELECT COUNT(*) FROM sacos WHERE closed_at IS NULL");
    int sacosCerrados = count("SELECT COUNT(*) FROM sacos WHERE closed_at IS NOT NULL");

    List<Map<String, Object>> byEstado = new ArrayList<>();
    byEstado.add(estadoRow(ESTADO_RECIBIDO, recibidosActual));
    byEstado.add(estadoRow(ESTADO_NO_ENTREGADO, noEntregadoDisponibleActual));
    byEstado.add(estadoRow(ESTADO_SEGUNDO_INTENTO, segundoIntentoActual));
    byEstado.add(estadoRow(ESTADO_POD, entregadosActual));
    byEstado.add(estadoRow(ESTADO_NO_ENTREGABLE, noEntregableActual));
    byEstado.add(
        estadoRow(
            "NO_ENTREGABLE__FUERA_DE_RUTA", snapshot.getOrDefault("NO_ENTREGABLE__FUERA_DE_RUTA", 0)));
    byEstado.add(
        estadoRow("NO_ENTREGABLE__VENCIDOS", snapshot.getOrDefault("NO_ENTREGABLE__VENCIDOS", 0)));
    byEstado.add(
        estadoRow(
            "NO_ENTREGABLE__DOS_INTENTOS", snapshot.getOrDefault("NO_ENTREGABLE__DOS_INTENTOS", 0)));

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

    if (fecha == null || fecha.isBlank()) {
      String sql =
          """
          SELECT u.id AS mensajero_id,
                u.full_name AS transportista,
                COALESCE(COUNT(DISTINCT p.id), 0) AS cantidad
          FROM usuarios u
          LEFT JOIN paquetes p
            ON p.mensajero_id = u.id
           AND p.estado = 'PRUEBA_DE_ENTREGA'
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
          limit);
    }

    LocalDate d = LocalDate.parse(fecha);
    Timestamp dIni = startOfDay(d);
    Timestamp dFinExcl = startOfNextDay(d);

    String sql =
        """
        WITH pod_rows AS (
          SELECT
            h.paquete_id,
            h.mensajero_id,
            CASE
              WHEN h.estado_to = 'PRUEBA_DE_ENTREGA' AND p.delivered_at IS NOT NULL THEN p.delivered_at
              ELSE h.changed_at
            END AS effective_at,
            ROW_NUMBER() OVER (
              PARTITION BY h.paquete_id
              ORDER BY
                CASE
                  WHEN h.estado_to = 'PRUEBA_DE_ENTREGA' AND p.delivered_at IS NOT NULL THEN p.delivered_at
                  ELSE h.changed_at
                END DESC,
                h.id DESC
            ) AS rn
          FROM paquete_estado_historial h
          JOIN paquetes p ON p.id = h.paquete_id
          WHERE h.estado_to = 'PRUEBA_DE_ENTREGA'
            AND (
              CASE
                WHEN h.estado_to = 'PRUEBA_DE_ENTREGA' AND p.delivered_at IS NOT NULL THEN p.delivered_at
                ELSE h.changed_at
              END
            ) >= ?
            AND (
              CASE
                WHEN h.estado_to = 'PRUEBA_DE_ENTREGA' AND p.delivered_at IS NOT NULL THEN p.delivered_at
                ELSE h.changed_at
              END
            ) < ?
        )
        SELECT
          u.id AS mensajero_id,
          u.full_name AS transportista,
          COALESCE(COUNT(pr.paquete_id), 0) AS cantidad
        FROM usuarios u
        LEFT JOIN pod_rows pr
          ON pr.mensajero_id = u.id
         AND pr.rn = 1
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
        dIni,
        dFinExcl,
        limit);
  }

  @GetMapping("/pods-transportista")
  public List<Map<String, Object>> podsPorTransportista(
      @RequestParam("mensajeroId") long mensajeroId,
      @RequestParam(value = "limit", defaultValue = "100000") int limit,
      @RequestParam(value = "fecha", required = false) String fecha) {

    if (fecha == null || fecha.isBlank()) {
      String sql =
          """
          SELECT p.id,
                p.tracking_code,
                s.marchamo,
                d.nombre AS distrito_nombre,
                p.estado,
                p.delivered_at,
                p.recipient_name,
                p.recipient_phone,
                p.recipient_address
          FROM paquetes p
          JOIN sacos s ON s.id = p.saco_id
          JOIN distritos d ON d.id = p.distrito_id
          WHERE p.estado = 'PRUEBA_DE_ENTREGA'
            AND p.mensajero_id = ?
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
            m.put("recipient_name", rs.getString("recipient_name"));
            m.put("recipient_phone", rs.getString("recipient_phone"));
            m.put("recipient_address", rs.getString("recipient_address"));
            return m;
          },
          mensajeroId,
          limit);
    }

    LocalDate d = LocalDate.parse(fecha);
    Timestamp dIni = startOfDay(d);
    Timestamp dFinExcl = startOfNextDay(d);

    String sql =
        """
        WITH pod_rows AS (
          SELECT
            h.id AS hist_id,
            h.paquete_id,
            h.mensajero_id,
            CASE
              WHEN h.estado_to = 'PRUEBA_DE_ENTREGA' AND p.delivered_at IS NOT NULL THEN p.delivered_at
              ELSE h.changed_at
            END AS delivered_event_at,
            ROW_NUMBER() OVER (
              PARTITION BY h.paquete_id
              ORDER BY
                CASE
                  WHEN h.estado_to = 'PRUEBA_DE_ENTREGA' AND p.delivered_at IS NOT NULL THEN p.delivered_at
                  ELSE h.changed_at
                END DESC,
                h.id DESC
            ) AS rn
          FROM paquete_estado_historial h
          JOIN paquetes p ON p.id = h.paquete_id
          WHERE h.estado_to = 'PRUEBA_DE_ENTREGA'
            AND h.mensajero_id = ?
            AND (
              CASE
                WHEN h.estado_to = 'PRUEBA_DE_ENTREGA' AND p.delivered_at IS NOT NULL THEN p.delivered_at
                ELSE h.changed_at
              END
            ) >= ?
            AND (
              CASE
                WHEN h.estado_to = 'PRUEBA_DE_ENTREGA' AND p.delivered_at IS NOT NULL THEN p.delivered_at
                ELSE h.changed_at
              END
            ) < ?
        )
        SELECT
          p.id,
          p.tracking_code,
          s.marchamo,
          d.nombre AS distrito_nombre,
          'PRUEBA_DE_ENTREGA' AS estado,
          pr.delivered_event_at AS delivered_at,
          p.recipient_name,
          p.recipient_phone,
          p.recipient_address
        FROM pod_rows pr
        JOIN paquetes p ON p.id = pr.paquete_id
        JOIN sacos s ON s.id = p.saco_id
        JOIN distritos d ON d.id = p.distrito_id
        WHERE pr.rn = 1
        ORDER BY pr.delivered_event_at DESC, p.id DESC
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
          m.put("recipient_name", rs.getString("recipient_name"));
          m.put("recipient_phone", rs.getString("recipient_phone"));
          m.put("recipient_address", rs.getString("recipient_address"));
          return m;
        },
        mensajeroId,
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
    Timestamp dIni = startOfDay(d);
    Timestamp dFinExcl = startOfNextDay(d);

    String sql =
        base
            + """
            WHERE CASE
              WHEN h.estado_to = 'PRUEBA_DE_ENTREGA' AND p.delivered_at IS NOT NULL THEN p.delivered_at
              WHEN h.estado_to = 'NO_ENTREGABLE' AND p.returned_at IS NOT NULL THEN p.returned_at
              WHEN h.estado_from IS NULL AND p.received_at IS NOT NULL THEN p.received_at
              ELSE h.changed_at
            END >= ?
            AND CASE
              WHEN h.estado_to = 'PRUEBA_DE_ENTREGA' AND p.delivered_at IS NOT NULL THEN p.delivered_at
              WHEN h.estado_to = 'NO_ENTREGABLE' AND p.returned_at IS NOT NULL THEN p.returned_at
              WHEN h.estado_from IS NULL AND p.received_at IS NOT NULL THEN p.received_at
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
