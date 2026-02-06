package com.cargosfsr.inventario.controllers;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class VigenciaController {

    private final JdbcTemplate jdbc;
    public VigenciaController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping("/busqueda/vigencia")
    public List<Map<String,Object>> vigencia(
        @RequestParam(required = false) Integer dias,
        @RequestParam(required = false) Integer desde,
        @RequestParam(required = false) Integer hasta,
        @RequestParam(defaultValue = "50") int limit,
        @RequestParam(defaultValue = "0")  int offset
    ) {
        int d1, d2;
        if (dias != null) { d1 = dias; d2 = dias; }
        else {
            d1 = (desde == null ? 0 : desde);
            d2 = (hasta == null ? d1 : hasta);
            if (d1 > d2) { int t = d1; d1 = d2; d2 = t; }
        }
        return jdbc.queryForList(
            "SELECT * " +
            "FROM vw_paquete_resumen v " +
            "WHERE v.estado='NO_ENTREGADO_CONSIGNATARIO_DISPONIBLE' " +
            "  AND DATEDIFF(CURDATE(), DATE(v.received_at)) BETWEEN ? AND ? " +
            "ORDER BY v.received_at ASC, v.id ASC " +
            "LIMIT ? OFFSET ?",
            d1, d2, limit, offset
        );
    }

    @GetMapping("/busqueda/vigencia/count")
    public Map<String,Object> vigenciaCount(
        @RequestParam(required = false) Integer dias,
        @RequestParam(required = false) Integer desde,
        @RequestParam(required = false) Integer hasta
    ) {
        int d1, d2;
        if (dias != null) { d1 = dias; d2 = dias; }
        else {
            d1 = (desde == null ? 0 : desde);
            d2 = (hasta == null ? d1 : hasta);
            if (d1 > d2) { int t = d1; d1 = d2; d2 = t; }
        }
        Long total = jdbc.queryForObject(
            "SELECT COUNT(*) " +
            "FROM vw_paquete_resumen v " +
            "WHERE v.estado='NO_ENTREGADO_CONSIGNATARIO_DISPONIBLE' " +
            "  AND DATEDIFF(CURDATE(), DATE(v.received_at)) BETWEEN ? AND ?",
            Long.class, d1, d2
        );
        return Map.of("total", total == null ? 0 : total);
    }
}
