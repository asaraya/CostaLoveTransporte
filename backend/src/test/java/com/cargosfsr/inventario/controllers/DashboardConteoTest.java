package com.cargosfsr.inventario.controllers;

import static org.junit.jupiter.api.Assertions.*;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import com.cargosfsr.inventario.services.ConsultasService;

class DashboardConteoTest {
    private JdbcTemplate jdbc;
    private DashboardController dashboard;
    private final String day = LocalDate.now().toString();

    @BeforeEach void setup() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE paquetes(id BIGINT PRIMARY KEY, tracking_code VARCHAR(100) UNIQUE, estado VARCHAR(80), devolucion_subtipo VARCHAR(40), received_at TIMESTAMP, delivered_at TIMESTAMP, returned_at TIMESTAMP, last_state_change_at TIMESTAMP, mensajero_id BIGINT, saco_id BIGINT, distrito_id BIGINT, recipient_name VARCHAR(100), recipient_phone VARCHAR(100), recipient_address VARCHAR(100))");
        jdbc.execute("CREATE TABLE paquete_estado_historial(id BIGINT PRIMARY KEY, paquete_id BIGINT, estado_from VARCHAR(80), estado_to VARCHAR(80), changed_at TIMESTAMP, mensajero_id BIGINT)");
        jdbc.execute("CREATE TABLE usuarios(id BIGINT PRIMARY KEY, full_name VARCHAR(100), rol VARCHAR(40))");
        jdbc.execute("CREATE TABLE sacos(id BIGINT PRIMARY KEY, marchamo VARCHAR(100), closed_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE distritos(id BIGINT PRIMARY KEY, nombre VARCHAR(100))");
        jdbc.update("INSERT INTO usuarios VALUES(1,'Uno','MENSAJERO'),(2,'Dos','MENSAJERO')");
        jdbc.update("INSERT INTO sacos VALUES(1,'S1',NULL)");
        jdbc.update("INSERT INTO distritos VALUES(1,'Prueba')");
        dashboard = new DashboardController(jdbc);
    }

    private void paquete(int id, String estado, String delivered) {
        jdbc.update("INSERT INTO paquetes(id,tracking_code,estado,received_at,delivered_at,last_state_change_at,mensajero_id,saco_id,distrito_id) VALUES(?,?,?,?,?,?,2,1,1)",
                id, "CR"+id, estado, day+" 08:00:00", delivered, day+" 12:00:00");
    }

    @SuppressWarnings("unchecked")
    @Test void revertedDeliveryAndRepeatedMovementsAreNotExtraPackages() {
        paquete(1,"PRUEBA_DE_ENTREGA",day+" 10:00:00");
        paquete(2,"NO_ENTREGADO_CONSIGNATARIO_DISPONIBLE",null);
        jdbc.update("INSERT INTO paquete_estado_historial VALUES(1,1,'ENTREGADO_A_TRANSPORTISTA_LOCAL','PRUEBA_DE_ENTREGA',?,1),(2,1,'PRUEBA_DE_ENTREGA','PRUEBA_DE_ENTREGA',?,2),(3,2,'ENTREGADO_A_TRANSPORTISTA_LOCAL','PRUEBA_DE_ENTREGA',?,1),(4,2,'PRUEBA_DE_ENTREGA','NO_ENTREGADO_CONSIGNATARIO_DISPONIBLE',?,NULL)",
                day+" 09:00:00", day+" 10:00:00", day+" 11:00:00", day+" 12:00:00");
        var result = dashboard.summary(day);
        var hoy = (Map<String,Object>) result.get("hoy");
        assertEquals(1, hoy.get("entregados")); assertEquals(1, result.get("inventarioActual"));
        assertEquals(1L, new ConsultasService(jdbc).reporteDiarioFlat(LocalDate.now()).get("entregado"));
        var ranking = dashboard.topTransportistas(10,day);
        assertEquals(1, ranking.stream().mapToInt(r -> ((Number)r.get("cantidad")).intValue()).sum());
        assertEquals(0, dashboard.podsPorTransportista(1,10,day).size());
        assertEquals(1, dashboard.podsPorTransportista(2,10,day).size());
    }

    @Test void historicalSnapshotUsesEventDateNotLatestDeliveryDate() {
        String previous = LocalDate.now().minusDays(2).toString();
        paquete(1,"PRUEBA_DE_ENTREGA",day+" 10:00:00");
        jdbc.update("UPDATE paquetes SET received_at = ? WHERE id=1", previous+" 08:00:00");
        jdbc.update("INSERT INTO paquete_estado_historial VALUES(1,1,NULL,'ENTREGADO_A_TRANSPORTISTA_LOCAL',?,NULL),(2,1,'ENTREGADO_A_TRANSPORTISTA_LOCAL','PRUEBA_DE_ENTREGA',?,2)", previous+" 08:00:00",previous+" 10:00:00");
        assertEquals(0,dashboard.summary(previous).get("inventarioFecha"));
    }

    @Test void administrativeSelfTransitionDoesNotOverrideHistoricalState() {
        String previous = LocalDate.now().minusDays(2).toString();
        paquete(1,"PRUEBA_DE_ENTREGA",day+" 10:00:00");
        jdbc.update("UPDATE paquetes SET received_at = ? WHERE id=1", previous+" 08:00:00");
        jdbc.update("INSERT INTO paquete_estado_historial VALUES(1,1,NULL,'ENTREGADO_A_TRANSPORTISTA_LOCAL',?,NULL),(2,1,'ENTREGADO_A_TRANSPORTISTA_LOCAL','PRUEBA_DE_ENTREGA',?,2),(3,1,'ENTREGADO_A_TRANSPORTISTA_LOCAL','ENTREGADO_A_TRANSPORTISTA_LOCAL',?,NULL)", previous+" 08:00:00",previous+" 10:00:00",previous+" 11:00:00");
        assertEquals(0,dashboard.summary(previous).get("inventarioFecha"));
    }
}
