package com.cargosfsr.inventario.controllers;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.cargosfsr.inventario.model.PaqueteEstado;
import com.cargosfsr.inventario.services.EstadoService;

@RestController
@RequestMapping("/api/busqueda")
public class AvisosController {

    private final JdbcTemplate jdbc;
    private final EstadoService estadoService;

    public AvisosController(JdbcTemplate jdbc, EstadoService estadoService) {
        this.jdbc = jdbc;
        this.estadoService = estadoService;
    }

    private enum AvisoTipo { INTENTO_1, INTENTO_2, NO_ENTREGABLE }

    private static class AvisoRule {
        final String whereSql;
        final PaqueteEstado estadoSugerido;
        final int minDias;
        final Integer maxDiasExclusive;

        AvisoRule(String whereSql, PaqueteEstado estadoSugerido, int minDias, Integer maxDiasExclusive) {
            this.whereSql = whereSql;
            this.estadoSugerido = estadoSugerido;
            this.minDias = minDias;
            this.maxDiasExclusive = maxDiasExclusive;
        }
    }

    private static AvisoTipo parseTipo(String s) {
        String t = (s == null ? "" : s).trim().toUpperCase(Locale.ROOT);
        try { return AvisoTipo.valueOf(t); } catch (Exception e) { return null; }
    }

    private static AvisoRule rule(AvisoTipo tipo) {
        if (tipo == null) return null;
        switch (tipo) {
            case INTENTO_1:
                return new AvisoRule(
                        "v.estado = 'ENTREGADO_A_TRANSPORTISTA_LOCAL'",
                        PaqueteEstado.NO_ENTREGADO_CONSIGNATARIO_DISPONIBLE,
                        3,
                        4
                );
            case INTENTO_2:
                return new AvisoRule(
                        "v.estado IN ('ENTREGADO_A_TRANSPORTISTA_LOCAL','NO_ENTREGADO_CONSIGNATARIO_DISPONIBLE')",
                        PaqueteEstado.ENTREGADO_A_TRANSPORTISTA_LOCAL_2DO_INTENTO,
                        4,
                        7
                );
            case NO_ENTREGABLE:
                return new AvisoRule(
                        "v.estado IN ('ENTREGADO_A_TRANSPORTISTA_LOCAL','NO_ENTREGADO_CONSIGNATARIO_DISPONIBLE','ENTREGADO_A_TRANSPORTISTA_LOCAL_2DO_INTENTO')",
                        PaqueteEstado.NO_ENTREGABLE,
                        7,
                        null
                );
            default:
                return null;
        }
    }

    private static class DateWindow {
        final String startInclusive;
        final String endExclusive;
        final boolean hasRange;
        DateWindow(String startInclusive, String endExclusive, boolean hasRange) {
            this.startInclusive = startInclusive;
            this.endExclusive = endExclusive;
            this.hasRange = hasRange;
        }
    }

    private static DateWindow windowFor(LocalDate today, AvisoRule r) {
        if (r.maxDiasExclusive != null) {
            int startDaysAgo = r.maxDiasExclusive - 1;
            int endDaysAgo = r.minDias - 1;
            String start = today.minusDays(startDaysAgo) + " 00:00:00";
            String end = today.minusDays(endDaysAgo) + " 00:00:00";
            return new DateWindow(start, end, true);
        }
        int cutoffDaysAgo = r.minDias - 1;
        String cutoff = today.minusDays(cutoffDaysAgo) + " 00:00:00";
        return new DateWindow(null, cutoff, false);
    }

    @GetMapping("/avisos")
    public List<Map<String, Object>> listAvisos(
            @RequestParam("tipo") String tipo,
            @RequestParam(value = "limit", defaultValue = "50") int limit,
            @RequestParam(value = "offset", defaultValue = "0") int offset
    ) {
        AvisoTipo t = parseTipo(tipo);
        AvisoRule r = rule(t);
        if (r == null) return Collections.emptyList();

        LocalDate today = LocalDate.now();
        DateWindow w = windowFor(today, r);

        StringBuilder sql = new StringBuilder();
        sql.append("""
                SELECT v.id, v.tracking_code, v.marchamo, v.estado, v.received_at,
                       v.recipient_name, v.recipient_phone, v.recipient_address, v.distrito_nombre
                FROM vw_paquete_resumen v
                WHERE
                """);
        sql.append(r.whereSql);

        List<Object> args = new ArrayList<>();

        if (w.hasRange) {
            sql.append(" AND v.received_at >= ? AND v.received_at < ? ");
            args.add(w.startInclusive);
            args.add(w.endExclusive);
        } else {
            sql.append(" AND v.received_at < ? ");
            args.add(w.endExclusive);
        }

        sql.append(" ORDER BY v.received_at ASC, v.id ASC LIMIT ? OFFSET ? ");
        args.add(limit);
        args.add(offset);

        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    @GetMapping("/avisos/count")
    public Map<String, Object> countAvisos(@RequestParam("tipo") String tipo) {
        AvisoTipo t = parseTipo(tipo);
        AvisoRule r = rule(t);
        if (r == null) return Map.of("count", 0);

        LocalDate today = LocalDate.now();
        DateWindow w = windowFor(today, r);

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT COUNT(*) AS c FROM vw_paquete_resumen v WHERE ");
        sql.append(r.whereSql);

        List<Object> args = new ArrayList<>();
        if (w.hasRange) {
            sql.append(" AND v.received_at >= ? AND v.received_at < ? ");
            args.add(w.startInclusive);
            args.add(w.endExclusive);
        } else {
            sql.append(" AND v.received_at < ? ");
            args.add(w.endExclusive);
        }

        Long c = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
        return Map.of("count", c == null ? 0 : c);
    }

    @PostMapping("/avisos/aplicar")
    public Map<String, Object> aplicarAviso(@RequestParam("tipo") String tipo) {
        AvisoTipo t = parseTipo(tipo);
        AvisoRule r = rule(t);
        if (r == null) return Map.of("total", 0, "ok", 0, "fail", 0, "details", Collections.emptyList());

        LocalDate today = LocalDate.now();
        DateWindow w = windowFor(today, r);

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT v.tracking_code FROM vw_paquete_resumen v WHERE ");
        sql.append(r.whereSql);

        List<Object> args = new ArrayList<>();
        if (w.hasRange) {
            sql.append(" AND v.received_at >= ? AND v.received_at < ? ");
            args.add(w.startInclusive);
            args.add(w.endExclusive);
        } else {
            sql.append(" AND v.received_at < ? ");
            args.add(w.endExclusive);
        }

        List<String> trackings = jdbc.queryForList(sql.toString(), String.class, args.toArray());
        if (trackings == null || trackings.isEmpty()) {
            return Map.of("total", 0, "ok", 0, "fail", 0, "details", Collections.emptyList());
        }

        return estadoService.actualizarEstadoBulk(
                trackings,
                r.estadoSugerido,
                "AVISOS: " + tipo,
                null,
                false
        );
    }
}
