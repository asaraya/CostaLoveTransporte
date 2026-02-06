package com.cargosfsr.inventario.controllers;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/busqueda")
public class AvisosController {

    private final JdbcTemplate jdbc;

    public AvisosController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public enum AvisoTipo {
        INTENTO_1,
        INTENTO_2,
        NO_ENTREGABLE
    }

    private AvisoTipo parseTipo(String s) {
        if (s == null || s.isBlank()) throw new IllegalArgumentException("tipo requerido");
        try {
            return AvisoTipo.valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("tipo inválido: " + s + " (use INTENTO_1 | INTENTO_2 | NO_ENTREGABLE)");
        }
    }

    private static class AvisoRule {
        final String whereSql;
        final int minDias;

        AvisoRule(String whereSql, int minDias) {
            this.whereSql = whereSql;
            this.minDias = minDias;
        }
    }

    private AvisoRule rule(AvisoTipo tipo) {
        return switch (tipo) {
            case INTENTO_1 -> new AvisoRule(
                "v.estado = 'ENTREGADO_A_TRANSPORTISTA_LOCAL'",
                3
            );
            case INTENTO_2 -> new AvisoRule(
                "v.estado = 'NO_ENTREGADO_CONSIGNATARIO_DISPONIBLE'",
                4
            );
            case NO_ENTREGABLE -> new AvisoRule(
                "v.estado IN ('ENTREGADO_A_TRANSPORTISTA_LOCAL'," +
                "             'NO_ENTREGADO_CONSIGNATARIO_DISPONIBLE'," +
                "             'ENTREGADO_A_TRANSPORTISTA_LOCAL_2DO_INTENTO')",
                7
            );
        };
    }

    @GetMapping("/avisos")
    public List<Map<String, Object>> avisos(
            @RequestParam String tipo,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0")  int offset
    ) {
        AvisoTipo t = parseTipo(tipo);
        AvisoRule r = rule(t);

        int lim = Math.max(1, Math.min(limit, 200000));
        int off = Math.max(0, offset);

        String sql =
            "SELECT * " +
            "FROM vw_paquete_resumen v " +
            "WHERE " + r.whereSql + " " +
            "  AND DATEDIFF(CURDATE(), DATE(v.received_at)) >= ? " +
            "ORDER BY v.received_at ASC, v.id ASC " +
            "LIMIT ? OFFSET ?";

        return jdbc.queryForList(sql, r.minDias, lim, off);
    }

    @GetMapping("/avisos/count")
    public Map<String, Object> avisosCount(@RequestParam String tipo) {
        AvisoTipo t = parseTipo(tipo);
        AvisoRule r = rule(t);

        String sql =
            "SELECT COUNT(*) " +
            "FROM vw_paquete_resumen v " +
            "WHERE " + r.whereSql + " " +
            "  AND DATEDIFF(CURDATE(), DATE(v.received_at)) >= ?";

        Long total = jdbc.queryForObject(sql, Long.class, r.minDias);
        return Collections.singletonMap("total", total == null ? 0 : total);
    }
}
