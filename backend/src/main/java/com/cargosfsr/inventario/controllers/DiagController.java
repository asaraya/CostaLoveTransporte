package com.cargosfsr.inventario.controllers;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/diag", produces = MediaType.APPLICATION_JSON_VALUE)
public class DiagController {

    private final JdbcTemplate jdbc;
    private final Environment env;

    private static final String TZ_ID = "America/Costa_Rica";
    private static final ZoneId TZ_CR = ZoneId.of(TZ_ID);
    private static final DateTimeFormatter ISO_CR = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(TZ_CR);

    public DiagController(JdbcTemplate jdbc, Environment env) {
        this.jdbc = jdbc;
        this.env = env;
    }

    @GetMapping("/time")
    public Map<String, Object> time() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("jvm_default_zone", ZoneId.systemDefault().getId());
        out.put("jackson_tz_prop", env.getProperty("spring.jackson.time-zone"));
        out.put("java_tool_options", System.getenv("JAVA_TOOL_OPTIONS"));

        Instant now = Instant.now();
        out.put("instant_utc_toString", now.toString());
        out.put("instant_as_CR_ISO", ISO_CR.format(now));

        ZonedDateTime zNowCR = ZonedDateTime.now(TZ_CR);
        out.put("zoned_now_CR", ISO_CR.format(zNowCR));

        out.put("hibernate.jdbc.time_zone", env.getProperty("spring.jpa.properties.hibernate.jdbc.time_zone"));
        return out;
    }

    @GetMapping("/env")
    public Map<String, Object> env() throws Exception {
        Map<String, Object> out = new LinkedHashMap<>();
        try (Connection c = Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
            DatabaseMetaData md = c.getMetaData();
            out.put("jdbcUrl", md.getURL());
            out.put("dbUser", md.getUserName());
        }
        var session = jdbc.queryForMap("CALL sp_th_diagnostico_sesion()");
        out.put("database()", session.get("db"));
        out.put("@@global.time_zone", session.get("global_tz"));
        out.put("@@session.time_zone", session.get("session_tz"));
        out.put("@@sql_mode", session.get("modo"));
        out.put("now()", session.get("ahora"));
        return out;
    }

    @GetMapping("/jdbc")
    public Map<String, Object> jdbcInfo() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("spring.datasource.url", env.getProperty("spring.datasource.url"));
        out.put("hikari.connectionInitSql", env.getProperty("spring.datasource.hikari.connection-init-sql"));
        return out;
    }

    @GetMapping("/db")
    public Map<String, Object> db() {
        Map<String, Object> q = jdbc.queryForMap(
            "CALL sp_th_diagnostico_zonas(?)",
            TZ_ID
        );

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mysql_global_time_zone", q.get("global_tz"));
        out.put("mysql_session_time_zone", q.get("session_tz"));
        out.put("mysql_now_session", q.get("now_session"));
        out.put("mysql_now_utc", q.get("now_utc"));
        out.put("mysql_now_cr_via_convert", q.get("now_cr"));
        return out;
    }

    @GetMapping("/counts")
    public Map<String, Object> counts(@RequestParam String fecha) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("fecha", fecha);

        out.put("recibidos_DATE",  jdbc.queryForObject("CALL sp_th_diagnostico_recibidos(?)", Integer.class, fecha));
        out.put("entregados_DATE", jdbc.queryForObject("CALL sp_th_diagnostico_entregas(?)", Integer.class, fecha));
        out.put("no_entregable_DATE", jdbc.queryForObject("CALL sp_th_diagnostico_devoluciones(?)", Integer.class, fecha));

        out.put("disponible_total", jdbc.queryForObject(
            "CALL sp_th_diagnostico_disponibles()", Integer.class));

        out.put("entregado_total", jdbc.queryForObject(
            "CALL sp_th_diagnostico_transportista()",
            Integer.class));

        out.put("no_entregable_total", jdbc.queryForObject(
            "CALL sp_th_diagnostico_no_entregables()", Integer.class));

        return out;
    }

    @GetMapping("/sps")
    public Map<String, Object> sps() {
        Map<String, Object> out = new LinkedHashMap<>();
        List<String> names = List.of(
            "sp_reporte_diario",
            "sp_paquetes_entregados",
            "sp_paquetes_devolucion",
            "sp_paquetes_por_fecha",
            "sp_paquetes_por_distrito",
            "sp_paquetes_por_ubicacion",
            "sp_tracking_distrito",
            "sp_tracking_ubicacion",
            "sp_set_marchamo_distrito",
            "sp_set_marchamo_ubicacion",
            "sp_agregar_mueble"
        );
        for (String n : names) {
            Map<String, Object> row = null;
            try { row = jdbc.queryForMap("CALL sp_th_diagnostico_rutina(?)", n); }
            catch (Exception ignore) {}

            Map<String, Object> info = new LinkedHashMap<>();
            info.put("exists", row != null && !row.isEmpty());
            if (Boolean.TRUE.equals(info.get("exists"))) {
                try {
                    Map<String, Object> create = jdbc.queryForMap("CALL sp_th_diagnostico_definicion(?)", n);
                    String body = (String) create.get("Create Procedure");
                    info.put("length", body == null ? 0 : body.length());
                    info.put("checksum", body == null ? null : Integer.toHexString(body.hashCode()));
                } catch (Exception e) {
                    info.put("error_read", e.getMessage());
                }
            }
            out.put(n, info);
        }
        return out;
    }
}
