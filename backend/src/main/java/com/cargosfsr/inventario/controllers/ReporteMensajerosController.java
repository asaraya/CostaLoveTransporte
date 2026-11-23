package com.cargosfsr.inventario.controllers;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import com.cargosfsr.inventario.services.FechasPaquete;

@RestController
@RequestMapping("/api/reportes/mensajeros")
@Transactional(readOnly = true)
public class ReporteMensajerosController {
    private final JdbcTemplate jdbc;
    public ReporteMensajerosController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping("/opciones")
    public List<Map<String, Object>> opciones() {
        // Incluir usuarios inactivos que tienen entregas, para consultar períodos anteriores.
        return jdbc.queryForList("CALL sp_th_mensajeros_opciones()");
    }

    @GetMapping
    public List<Map<String, Object>> entregas(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
            @RequestParam(required = false) Long mensajeroId) {
        if (desde == null || hasta == null || desde.isAfter(hasta) || hasta.getYear() > 9998
                || desde.getYear() < 1000 || (mensajeroId != null && mensajeroId < 0)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Revise el rango de fechas y el mensajero");
        }
        return FechasPaquete.completar(jdbc, jdbc.queryForList("CALL sp_th_mensajeros_reporte(?, ?, ?)",
                Timestamp.valueOf(desde.atStartOfDay()), Timestamp.valueOf(hasta.plusDays(1).atStartOfDay()), mensajeroId));
    }
}
