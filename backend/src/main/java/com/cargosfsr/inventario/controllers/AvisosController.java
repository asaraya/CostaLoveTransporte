package com.cargosfsr.inventario.controllers;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import com.cargosfsr.inventario.services.FechasPaquete;
import java.util.Locale;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.cargosfsr.inventario.auth.CurrentUser;
import com.cargosfsr.inventario.model.enums.PaqueteEstado;
import com.cargosfsr.inventario.services.EstadoService;

@RestController
@RequestMapping("/api/busqueda")
public class AvisosController {

    private final JdbcTemplate jdbc;
    private final EstadoService estadoService;
    private final CurrentUser currentUser;

    public AvisosController(JdbcTemplate jdbc, EstadoService estadoService, CurrentUser currentUser) {
        this.jdbc = jdbc;
        this.estadoService = estadoService;
        this.currentUser = currentUser;
    }

    private enum AvisoTipo { INTENTO_1, INTENTO_2, NO_ENTREGABLE }

    private static class AvisoRule {
        final PaqueteEstado estadoSugerido;
        final int minDias;
        final Integer maxDiasExclusive;

        AvisoRule(PaqueteEstado estadoSugerido, int minDias, Integer maxDiasExclusive) {
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
                        PaqueteEstado.NO_ENTREGADO_CONSIGNATARIO_DISPONIBLE,
                        3,
                        4
                );
            case INTENTO_2:
                return new AvisoRule(
                        PaqueteEstado.ENTREGADO_A_TRANSPORTISTA_LOCAL_2DO_INTENTO,
                        4,
                        7
                );
            case NO_ENTREGABLE:
                return new AvisoRule(
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

        DateWindow(String startInclusive, String endExclusive) {
            this.startInclusive = startInclusive;
            this.endExclusive = endExclusive;
        }
    }

    private static DateWindow windowFor(LocalDate today, AvisoRule r) {
        if (r.maxDiasExclusive != null) {
            int startDaysAgo = r.maxDiasExclusive - 1;
            int endDaysAgo = r.minDias - 1;
            String start = today.minusDays(startDaysAgo) + " 00:00:00";
            String end = today.minusDays(endDaysAgo) + " 00:00:00";
            return new DateWindow(start, end);
        }
        int cutoffDaysAgo = r.minDias - 1;
        String cutoff = today.minusDays(cutoffDaysAgo) + " 00:00:00";
        return new DateWindow(null, cutoff);
    }

    private long countFor(AvisoTipo tipo) {
        AvisoRule r = rule(tipo);
        if (r == null) return 0;

        LocalDate today = LocalDate.now();
        DateWindow w = windowFor(today, r);

        Long c = jdbc.queryForObject("CALL sp_th_avisos_contar(?, ?, ?)", Long.class, tipo.name(), w.startInclusive, w.endExclusive);
        return c == null ? 0 : c.longValue();

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

        return FechasPaquete.completar(jdbc, jdbc.queryForList("CALL sp_th_avisos_listar(?, ?, ?, ?, ?)", t.name(), w.startInclusive, w.endExclusive, limit, offset));

    }

    @GetMapping("/avisos/count")
    public Map<String, Object> countAvisos(@RequestParam("tipo") String tipo) {
        AvisoTipo t = parseTipo(tipo);
        long total = (t == null) ? 0 : countFor(t);
        return Map.of("total", total);
    }

    @GetMapping("/avisos/summary")
    public Map<String, Object> summaryAvisos() {
        long i1 = countFor(AvisoTipo.INTENTO_1);
        long i2 = countFor(AvisoTipo.INTENTO_2);
        long ne = countFor(AvisoTipo.NO_ENTREGABLE);
        long total = i1 + i2 + ne;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("intento1", i1);
        out.put("intento2", i2);
        out.put("noEntregable", ne);
        out.put("total", total);
        out.put("hasAny", total > 0);
        return out;
    }

    @PostMapping("/avisos/aplicar")
    public Map<String, Object> aplicarAviso(@RequestParam("tipo") String tipo) {
        AvisoTipo t = parseTipo(tipo);
        AvisoRule r = rule(t);
        if (r == null) {
            return Map.of("total", 0, "ok", 0, "fail", 0, "details", Collections.emptyList());
        }

        LocalDate today = LocalDate.now();
        DateWindow w = windowFor(today, r);

        List<String> trackings = jdbc.queryForList("CALL sp_th_avisos_trackings(?, ?, ?)", String.class, t.name(), w.startInclusive, w.endExclusive);
        if (trackings == null || trackings.isEmpty()) {
            return Map.of("total", 0, "ok", 0, "fail", 0, "details", Collections.emptyList());
        }

        String by = null;
        try { by = currentUser.display(); } catch (Exception ignored) {}

        return estadoService.actualizarEstadoBulk(
                trackings,
                r.estadoSugerido,
                "AVISOS: " + String.valueOf(tipo),
                by,
                false,
                Instant.now(),
                null,
                null
        );
    }
}
