package com.cargosfsr.inventario.services;

import static org.junit.jupiter.api.Assertions.*;
import com.cargosfsr.inventario.MysqlPruebas;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** Pruebas optativas contra MySQL desechable; nunca acepta una URL de producción. */
@EnabledIfSystemProperty(named = "test.mysql.url", matches = ".+")
class FechasMysqlTest {
    JdbcTemplate jdbc;
    SingleConnectionDataSource dataSource;

    @BeforeEach void prepare() {
        dataSource = MysqlPruebas.conectar();
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP TABLE IF EXISTS paquete_audit_log");
        jdbc.execute("DROP TABLE IF EXISTS paquete_estado_historial");
        jdbc.execute("DROP TABLE IF EXISTS paquetes");
        jdbc.execute("""
            CREATE TABLE paquetes (id BIGINT PRIMARY KEY AUTO_INCREMENT,
              estado VARCHAR(80), devolucion_subtipo VARCHAR(40),
              tracking_code VARCHAR(100) UNIQUE, received_at DATETIME, delivered_at DATETIME,
              returned_at DATETIME, last_state_change_at DATETIME)
            """);
        jdbc.execute("""
            CREATE TABLE paquete_estado_historial (id BIGINT PRIMARY KEY AUTO_INCREMENT,
              paquete_id BIGINT, estado_from VARCHAR(80), estado_to VARCHAR(80), changed_at DATETIME)
            """);
        jdbc.execute("""
            CREATE TABLE paquete_audit_log (id BIGINT PRIMARY KEY AUTO_INCREMENT,
              paquete_id BIGINT, tracking_code VARCHAR(100), accion VARCHAR(80), modulo_origen VARCHAR(80),
              descripcion VARCHAR(700), campo_afectado VARCHAR(120), valor_anterior TEXT, valor_nuevo TEXT,
              usuario VARCHAR(120), fecha_hora DATETIME DEFAULT CURRENT_TIMESTAMP,
              batch_id BIGINT, estado_historial_id BIGINT, detalle_json JSON)
            """);
    }

    @org.junit.jupiter.api.AfterEach void close() { if (dataSource != null) dataSource.destroy(); }

    void migrate() throws Exception {
        MysqlPruebas.migrar(jdbc);
    }

    void packageRow(int id, String tracking) {
        jdbc.update("INSERT INTO paquetes(id,tracking_code,received_at,last_state_change_at) VALUES(?,?,'2026-10-20 00:00:00','2026-10-21 12:00:00')", id, tracking);
    }

    @Test void migrationDoesNotChangeRecordsAndReadsRecoverUnambiguousDates() throws Exception {
        packageRow(1, "LM1"); packageRow(2, "LM2"); packageRow(3, "LM3");
        jdbc.execute("""
            INSERT INTO paquete_audit_log(paquete_id, tracking_code, accion, modulo_origen, fecha_hora,
              valor_anterior, valor_nuevo, estado_historial_id) VALUES
            (1,'LM1','CREACION_PAQUETE','RECEPCION','2026-10-22 14:35:38',NULL,'INICIO',NULL),
            (1,'LM1','CAMBIO_ESTADO','CAMBIO_STATUS','2026-10-22 15:04:05','INICIO','ENTREGADO',NULL),
            (2,'LM2','CREACION_PAQUETE','HISTORIAL_EXISTENTE','2026-10-20 00:00:00',NULL,'INICIO',NULL),
            (2,'LM2','CAMBIO_ESTADO','CAMBIO_STATUS','2026-10-22 15:00:00','INICIO','ENTREGADO',NULL),
            (3,'LM3','CAMBIO_ESTADO','HISTORIAL_EXISTENTE','2026-10-21 12:00:00','INICIO','ENTREGADO',4)
            """);
        jdbc.execute("""
            INSERT INTO paquete_estado_historial(id,paquete_id,estado_from,estado_to,changed_at) VALUES
            (1,1,'INICIO','ENTREGADO','2026-10-21 12:00:00'),
            (2,2,'INICIO','ENTREGADO','2026-10-21 12:00:00'),
            (3,2,'INICIO','ENTREGADO','2026-10-23 12:00:00'),
            (4,3,'INICIO','ENTREGADO','2026-10-21 12:00:00')
            """);
        var originalPackages = jdbc.queryForList("SELECT * FROM paquetes");
        var originalAudits = jdbc.queryForList("SELECT * FROM paquete_audit_log");
        migrate();
        var before = jdbc.queryForList("SELECT * FROM paquetes");
        var auditBefore = jdbc.queryForList("SELECT * FROM paquete_audit_log");
        migrate();
        assertEquals(before, jdbc.queryForList("SELECT * FROM paquetes"));
        assertEquals(auditBefore, jdbc.queryForList("SELECT * FROM paquete_audit_log"));
        assertNull(jdbc.queryForObject("SELECT registered_at FROM paquetes WHERE id=1", Object.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM paquete_audit_log WHERE effective_at IS NOT NULL", Integer.class));
        for (int i = 0; i < originalPackages.size(); i++) {
            var current = new java.util.LinkedHashMap<>(before.get(i));
            current.remove("registered_at");
            assertEquals(originalPackages.get(i), current);
        }
        for (int i = 0; i < originalAudits.size(); i++) {
            var current = new java.util.LinkedHashMap<>(auditBefore.get(i));
            current.remove("effective_at");
            assertEquals(originalAudits.get(i), current);
        }
        var dates = FechasPaquete.completar(jdbc, List.of(Map.of("tracking_code", "LM1"), Map.of("tracking_code", "LM2")));
        assertEquals("2026-10-22T14:35:38-06:00", dates.get(0).get("registered_at"));
        assertNull(dates.get(1).get("registered_at"));
        assertNull(jdbc.queryForObject("SELECT registered_at FROM paquetes WHERE id=2", Object.class));
        assertNull(jdbc.queryForObject("SELECT effective_at FROM paquete_audit_log WHERE id=4", Object.class));
        var audit = new PaqueteAuditService(jdbc, null);
        var result = (List<Map<String,Object>>) audit.historialPorTracking("LM1").get("items");
        assertEquals("2026-10-21T12:00:00-06:00", result.get(0).get("effective_at"));
        assertEquals("2026-10-22T15:04:05-06:00", result.get(0).get("fecha_hora"));
        assertEquals("2026-10-20T00:00:00-06:00", result.get(1).get("received_at_reference"));
        var ambiguous = (List<Map<String,Object>>) audit.historialPorTracking("LM2").get("items");
        assertNull(ambiguous.get(0).get("effective_at"));
        var linked = (List<Map<String,Object>>) audit.historialPorTracking("LM3").get("items");
        assertEquals("2026-10-21T12:00:00-06:00", linked.get(0).get("effective_at"));
        assertEquals(before, jdbc.queryForList("SELECT * FROM paquetes"));
        assertEquals(auditBefore, jdbc.queryForList("SELECT * FROM paquete_audit_log"));
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM paquetes", Integer.class));
        assertEquals(5, jdbc.queryForObject("SELECT COUNT(*) FROM paquete_audit_log", Integer.class));
    }

    @Test void courierReportUsesDeliveryDateAndDoesNotMultiplyRoutes() throws Exception {
        migrate();
        jdbc.execute("ALTER TABLE paquetes ADD mensajero_id BIGINT, ADD distrito_id BIGINT, ADD saco_id BIGINT, ADD recipient_name VARCHAR(100), ADD recipient_phone VARCHAR(40), ADD recipient_address VARCHAR(200)");
        for (String table : List.of("hoja_ruta_detalle", "hojas_ruta", "usuarios", "distritos", "sacos")) jdbc.execute("DROP TABLE IF EXISTS " + table);
        jdbc.execute("CREATE TABLE usuarios(id BIGINT PRIMARY KEY, full_name VARCHAR(100), rol VARCHAR(30), active BOOLEAN)");
        jdbc.execute("CREATE TABLE distritos(id BIGINT PRIMARY KEY, nombre VARCHAR(100))");
        jdbc.execute("CREATE TABLE sacos(id BIGINT PRIMARY KEY, marchamo VARCHAR(100))");
        jdbc.execute("CREATE TABLE hojas_ruta(id BIGINT PRIMARY KEY, transportista_id BIGINT, fecha DATE)");
        jdbc.execute("CREATE TABLE hoja_ruta_detalle(hoja_ruta_id BIGINT, paquete_id BIGINT)");
        jdbc.execute("INSERT INTO usuarios VALUES(1,'Ana','MENSAJERO',true),(2,'Ana','MENSAJERO',false)");
        jdbc.execute("INSERT INTO distritos VALUES(1,'Heredia')");
        jdbc.execute("INSERT INTO sacos VALUES(1,'123')");
        for (int i = 1; i <= 6; i++) packageRow(i, "LM" + i);
        jdbc.execute("UPDATE paquetes SET estado='PRUEBA_DE_ENTREGA',mensajero_id=1,distrito_id=1,saco_id=1,delivered_at='2026-10-15 23:59:59'");
        jdbc.execute("UPDATE paquetes SET delivered_at='2026-10-16 00:00:00',mensajero_id=2 WHERE id=2");
        jdbc.execute("UPDATE paquetes SET delivered_at='2026-09-30 23:59:59' WHERE id=3");
        jdbc.execute("UPDATE paquetes SET delivered_at='2026-11-01 00:00:00' WHERE id=4");
        jdbc.execute("UPDATE paquetes SET estado='NO_ENTREGABLE' WHERE id=5");
        jdbc.execute("UPDATE paquetes SET mensajero_id=NULL WHERE id=6");
        jdbc.execute("INSERT INTO hojas_ruta VALUES(1,1,'2026-10-13'),(2,1,'2026-10-14'),(3,2,'2026-10-15'),(4,1,'2026-10-16')");
        jdbc.execute("INSERT INTO hoja_ruta_detalle VALUES(1,1),(2,1),(3,1),(4,1)");
        var controller = new com.cargosfsr.inventario.controllers.ReporteMensajerosController(jdbc);
        var from = java.time.LocalDate.of(2026,10,1);
        var to = java.time.LocalDate.of(2026,10,31);
        var all = controller.entregas(from, to, null);
        assertEquals(3, all.size());
        var ana = controller.entregas(from, to, 1L);
        assertEquals(1, ana.size());
        assertEquals("LM1", ana.get(0).get("tracking_code"));
        assertEquals("2026-10-14", ana.get(0).get("fecha_hoja_ruta"));
        assertEquals("2026-10-15T23:59:59-06:00", ana.get(0).get("delivered_at"));
        assertEquals(2, controller.entregas(from, from.withDayOfMonth(15), null).size());
        assertEquals(1, controller.entregas(from.withDayOfMonth(16), to, null).size());
        assertEquals(1, controller.entregas(from, to, 0L).size());
        assertEquals(2, controller.opciones().size());
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> controller.entregas(to, from, null));
    }

    @Test void reimportarLoteConservaCadaTrackingUnaVez() throws Exception {
        jdbc.execute("ALTER TABLE paquetes ADD saco_id BIGINT, ADD distrito_id BIGINT, ADD ubicacion_id BIGINT");
        migrate();
        var lote = List.of(new Object[]{"CR123", 1, 2, 3}, new Object[]{"LM'Á,456", 1, 2, 3});
        String importar = "CALL sp_th_importar_paquete_base(?, ?, ?, ?)";
        jdbc.batchUpdate(importar, lote);
        jdbc.batchUpdate(importar, lote);
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM paquetes", Integer.class));

        var trackings = ParametrosJson.lista(List.of("LM'Á,456", "LM'Á,456", "CR999"));
        assertEquals(List.of("LM'Á,456"), jdbc.queryForList(
                "CALL sp_th_trackings_existentes(?)", String.class, trackings));
    }

    @Test void newDatesStayIndependentThroughReimportAndSubsequentStateChanges() throws Exception {
        migrate();
        jdbc.execute("SET time_zone='+00:00'");
        packageRow(1, "LM1");
        Object registered = jdbc.queryForObject("SELECT registered_at FROM paquetes WHERE id=1", Object.class);
        assertNotNull(registered);
        assertTrue(jdbc.queryForObject("SELECT ABS(TIMESTAMPDIFF(SECOND, registered_at, DATE_SUB(UTC_TIMESTAMP(),INTERVAL 6 HOUR))) < 10 FROM paquetes WHERE id=1", Boolean.class));
        var audit = new PaqueteAuditService(jdbc, null);
        audit.registrar(1L, "LM1", "CREACION_PAQUETE", "RECEPCION", "Creación", "estado", null, "INICIO", "Test", null);
        audit.registrar(1L, "LM1", "CAMBIO_ESTADO", "CAMBIO_STATUS", "Entrega", "estado", "INICIO", "ENTREGADO", "Test", null);
        jdbc.execute("UPDATE paquetes SET received_at='2026-10-19 00:00:00', last_state_change_at='2026-10-23 00:00:00', delivered_at='2026-10-21 12:00:00' WHERE id=1");
        assertEquals(registered, jdbc.queryForObject("SELECT registered_at FROM paquetes WHERE id=1", Object.class));
        var result = (List<Map<String,Object>>) audit.historialPorTracking("LM1").get("items");
        assertEquals("2026-10-21T12:00:00-06:00", result.get(0).get("effective_at"));
        assertEquals("2026-10-20T00:00:00-06:00", result.get(1).get("effective_at"));
        assertTrue(jdbc.queryForObject("SELECT ABS(TIMESTAMPDIFF(SECOND,fecha_hora,DATE_SUB(UTC_TIMESTAMP(),INTERVAL 6 HOUR)))<10 FROM paquete_audit_log WHERE id=2", Boolean.class));
        var rows = FechasPaquete.completar(jdbc, List.of(Map.of("tracking_code","LM1","marchamo","123"), Map.of("tracking_code","LM1","marchamo","123")));
        assertEquals(2, rows.size()); // no sobreconteos ni eliminación de filas del resultado original
        assertEquals("2026-10-19T00:00:00-06:00", rows.get(0).get("received_at"));
        assertEquals("2026-10-21T12:00:00-06:00", rows.get(0).get("delivered_at"));
        assertNotNull(rows.get(0).get("registered_at"));
        assertEquals("123", rows.get(0).get("marchamo"));
    }
}
