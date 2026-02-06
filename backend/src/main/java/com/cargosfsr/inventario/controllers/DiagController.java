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
        out.put("database()", jdbc.queryForObject("SELECT DATABASE()", String.class));
        out.put("@@global.time_zone", jdbc.queryForObject("SELECT @@global.time_zone", String.class));
        out.put("@@session.time_zone", jdbc.queryForObject("SELECT @@session.time_zone", String.class));
        out.put("@@sql_mode", jdbc.queryForObject("SELECT @@sql_mode", String.class));
        out.put("now()", jdbc.queryForObject("SELECT NOW()", String.class));
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
            "SELECT " +
            "@@global.time_zone AS global_tz, " +
            "@@session.time_zone AS session_tz, " +
            "NOW() AS now_session, " +
            "UTC_TIMESTAMP() AS now_utc, " +
            "CONVERT_TZ(UTC_TIMESTAMP(),'UTC', ?) AS now_cr",
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

        out.put("recibidos_DATE",  jdbc.queryForObject("SELECT COUNT(*) FROM paquetes WHERE DATE(received_at)=?", Integer.class, fecha));
        out.put("entregados_DATE", jdbc.queryForObject("SELECT COUNT(*) FROM paquetes WHERE DATE(delivered_at)=?", Integer.class, fecha));
        out.put("no_entregable_DATE", jdbc.queryForObject("SELECT COUNT(*) FROM paquetes WHERE DATE(returned_at)=?", Integer.class, fecha));

        out.put("disponible_total", jdbc.queryForObject(
            "SELECT COUNT(*) FROM paquetes WHERE estado='NO_ENTREGADO_CONSIGNATARIO_DISPONIBLE'", Integer.class));

        out.put("entregado_total", jdbc.queryForObject(
            "SELECT COUNT(*) FROM paquetes WHERE estado IN ('ENTREGADO_A_TRANSPORTISTA_LOCAL','ENTREGADO_A_TRANSPORTISTA_LOCAL_2DO_INTENTO')",
            Integer.class));

        out.put("no_entregable_total", jdbc.queryForObject(
            "SELECT COUNT(*) FROM paquetes WHERE estado='NO_ENTREGABLE'", Integer.class));

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
            "sp_tracking_distrito",
            "sp_set_marchamo_distrito"
        );
        for (String n : names) {
            Map<String, Object> row = null;
            try { row = jdbc.queryForMap("SHOW PROCEDURE STATUS WHERE Db = DATABASE() AND Name = ?", n); }
            catch (Exception ignore) {}

            Map<String, Object> info = new LinkedHashMap<>();
            info.put("exists", row != null && !row.isEmpty());
            if (Boolean.TRUE.equals(info.get("exists"))) {
                try {
                    Map<String, Object> create = jdbc.queryForMap("SHOW CREATE PROCEDURE " + n);
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
