package com.cargosfsr.inventario.controllers;

import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.cargosfsr.inventario.auth.CurrentUser;
import com.cargosfsr.inventario.services.ParametrosJson;

@RestController
@RequestMapping(path = "/api/hojas-ruta", produces = MediaType.APPLICATION_JSON_VALUE)
public class HojaRutaController {

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final CurrentUser currentUser;

    public HojaRutaController(JdbcTemplate jdbc, CurrentUser currentUser) {
        this.jdbc = jdbc;
        this.namedJdbc = new NamedParameterJdbcTemplate(jdbc);
        this.currentUser = currentUser;
    }

    public static class GuardarHojaRutaReq {
        public String fecha;
        public Long transportistaId;
        public List<String> trackings;
    }

    public static class ActualizarHojaRutaReq {
        public List<String> trackings;
    }

    private String actor() {
        String v = currentUser == null ? null : currentUser.display();
        return (v == null || v.isBlank()) ? "sistema" : v;
    }

    private LocalDate parseFecha(String raw) {
        if (raw == null || raw.isBlank()) {
            return LocalDate.now();
        }
        return LocalDate.parse(raw.trim());
    }

    private List<String> normalizeTrackings(List<String> trackings) {
        if (trackings == null || trackings.isEmpty()) return List.of();

        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String raw : trackings) {
            if (raw == null) continue;
            String t = raw.trim().toUpperCase(Locale.ROOT);
            if (t.isBlank()) continue;
            out.add(t);
        }
        return new ArrayList<>(out);
    }

    private void validarTransportista(Long transportistaId) {
        if (transportistaId == null) {
            throw new IllegalArgumentException("transportista requerido");
        }

        Integer count = jdbc.queryForObject(
                "CALL sp_th_hoja_validar_mensajero(?)",
                Integer.class,
                transportistaId);

        if (count == null || count == 0) {
            throw new IllegalArgumentException("transportista inválido o inactivo");
        }
    }

    private Map<String, Map<String, Object>> cargarPaquetesPorTracking(List<String> trackings) {
        if (trackings == null || trackings.isEmpty()) return Collections.emptyMap();

        MapSqlParameterSource params = new MapSqlParameterSource("trackings", ParametrosJson.lista(trackings));
        List<Map<String, Object>> rows = namedJdbc.queryForList(
                "CALL sp_th_hoja_paquetes(:trackings)",
                params);

        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String tracking = String.valueOf(row.get("tracking_code")).toUpperCase(Locale.ROOT);
            out.put(tracking, row);
        }
        return out;
    }

    private void validarPaquetesExisten(List<String> trackings, Map<String, Map<String, Object>> paquetes) {
        List<String> faltantes = new ArrayList<>();
        for (String t : trackings) {
            if (!paquetes.containsKey(t)) faltantes.add(t);
        }
        if (!faltantes.isEmpty()) {
            throw new IllegalArgumentException("Trackings no encontrados: " + String.join(", ", faltantes));
        }
    }

    private void validarNoAsignadosMismoDia(LocalDate fecha, List<String> trackings, Long hojaActualId) {
        if (trackings == null || trackings.isEmpty()) return;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("fecha", Date.valueOf(fecha))
                .addValue("trackings", ParametrosJson.lista(trackings))
                .addValue("hojaActualId", hojaActualId == null ? -1L : hojaActualId);

        List<Map<String, Object>> rows = namedJdbc.queryForList(
                "CALL sp_th_hoja_conflictos(:fecha, :trackings, :hojaActualId)",
                params);

        if (!rows.isEmpty()) {
            List<String> msg = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                msg.add(row.get("tracking_code") + " ya está en hoja #" + row.get("hoja_ruta_id")
                        + " (" + row.get("transportista") + ")");
            }
            throw new IllegalArgumentException(String.join("; ", msg));
        }
    }

    private void insertarDetalle(long hojaRutaId, LocalDate fecha, List<String> trackings, Map<String, Map<String, Object>> paquetes) {
        if (trackings == null || trackings.isEmpty()) return;

        List<MapSqlParameterSource> batch = new ArrayList<>();
        int orden = 1;
        for (String tracking : trackings) {
            Map<String, Object> paquete = paquetes.get(tracking);
            if (paquete == null) continue;

            batch.add(new MapSqlParameterSource()
                    .addValue("hojaRutaId", hojaRutaId)
                    .addValue("fecha", Date.valueOf(fecha))
                    .addValue("paqueteId", paquete.get("id"))
                    .addValue("trackingCode", tracking)
                    .addValue("orden", orden++));
        }

        namedJdbc.batchUpdate(
                "CALL sp_th_hoja_detalle_crear(:hojaRutaId, :fecha, :paqueteId, :trackingCode, :orden)",
                batch.toArray(MapSqlParameterSource[]::new));
    }

    private List<Map<String, Object>> listarHojasPorFecha(LocalDate fecha) {
        List<Map<String, Object>> hojas = jdbc.queryForList(
                "CALL sp_th_hojas_por_fecha(?)",
                Date.valueOf(fecha));

        if (hojas.isEmpty()) return hojas;

        List<Long> ids = hojas.stream()
                .map(row -> ((Number) row.get("id")).longValue())
                .toList();

        MapSqlParameterSource params = new MapSqlParameterSource("ids", ParametrosJson.lista(ids));
        List<Map<String, Object>> detalles = namedJdbc.queryForList(
                "CALL sp_th_hoja_detalles(:ids)",
                params);

        Map<Long, List<Map<String, Object>>> detallesPorHoja = new LinkedHashMap<>();
        for (Map<String, Object> detalle : detalles) {
            Long hojaId = ((Number) detalle.get("hoja_ruta_id")).longValue();
            detallesPorHoja.computeIfAbsent(hojaId, k -> new ArrayList<>()).add(detalle);
        }

        for (Map<String, Object> hoja : hojas) {
            Long hojaId = ((Number) hoja.get("id")).longValue();
            hoja.put("paquetes", detallesPorHoja.getOrDefault(hojaId, List.of()));
        }

        return hojas;
    }

    private Map<String, Object> buscarHojaPorId(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "CALL sp_th_hoja_consultar(?)",
                id);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("hoja de ruta no encontrada");
        }
        return rows.get(0);
    }

    @GetMapping("/transportistas")
    public List<Map<String, Object>> transportistas() {
        return jdbc.queryForList(
                "CALL sp_th_hoja_mensajeros()");
    }

    @GetMapping
    public Map<String, Object> consultar(@RequestParam String fecha) {
        LocalDate f = parseFecha(fecha);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("fecha", f.toString());
        out.put("hojas", listarHojasPorFecha(f));
        return out;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Transactional
    public Map<String, Object> crear(@RequestBody GuardarHojaRutaReq body) {
        if (body == null) throw new IllegalArgumentException("datos requeridos");

        LocalDate fecha = parseFecha(body.fecha);
        validarTransportista(body.transportistaId);

        List<String> trackings = normalizeTrackings(body.trackings);
        if (trackings.isEmpty()) {
            throw new IllegalArgumentException("ingrese al menos un tracking");
        }

        Map<String, Map<String, Object>> paquetes = cargarPaquetesPorTracking(trackings);
        validarPaquetesExisten(trackings, paquetes);
        validarNoAsignadosMismoDia(fecha, trackings, null);

        String user = actor();
        Long hojaRutaId = jdbc.queryForObject("CALL sp_th_hoja_crear(?, ?, ?)",
                Long.class, Date.valueOf(fecha), body.transportistaId, user);
        if (hojaRutaId == null) throw new IllegalStateException("no se pudo crear la hoja de ruta");

        insertarDetalle(hojaRutaId, fecha, trackings, paquetes);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("id", hojaRutaId);
        out.put("fecha", fecha.toString());
        out.put("total", trackings.size());
        return out;
    }

    @PutMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Transactional
    public Map<String, Object> actualizar(@PathVariable long id, @RequestBody ActualizarHojaRutaReq body) {
        if (body == null) throw new IllegalArgumentException("datos requeridos");

        Map<String, Object> hoja = buscarHojaPorId(id);
        LocalDate fecha;
        Object fechaObj = hoja.get("fecha");
        if (fechaObj instanceof Date d) {
            fecha = d.toLocalDate();
        } else {
            fecha = LocalDate.parse(String.valueOf(fechaObj).substring(0, 10));
        }

        List<String> trackings = normalizeTrackings(body.trackings);

        // NUEVO:
        // Si la edición deja la hoja sin paquetes, se elimina por completo.
        if (trackings.isEmpty()) {
            int detallesEliminados = jdbc.update("CALL sp_th_hoja_detalles_eliminar(?)", id);
            int hojasEliminadas = jdbc.update("CALL sp_th_hoja_eliminar(?)", id);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("deleted", hojasEliminadas > 0);
            out.put("id", id);
            out.put("fecha", fecha.toString());
            out.put("detallesEliminados", detallesEliminados);
            return out;
        }

        Map<String, Map<String, Object>> paquetes = cargarPaquetesPorTracking(trackings);
        validarPaquetesExisten(trackings, paquetes);
        validarNoAsignadosMismoDia(fecha, trackings, id);

        jdbc.update("CALL sp_th_hoja_detalles_eliminar(?)", id);
        insertarDetalle(id, fecha, trackings, paquetes);

        jdbc.update(
                "CALL sp_th_hoja_actualizar_actor(?, ?)",
                actor(),
                id);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("deleted", false);
        out.put("id", id);
        out.put("fecha", fecha.toString());
        out.put("total", trackings.size());
        return out;
    }

    @DeleteMapping("/{id}")
    @Transactional
    public Map<String, Object> eliminar(@PathVariable long id) {
        buscarHojaPorId(id);

        int detallesEliminados = jdbc.update("CALL sp_th_hoja_detalles_eliminar(?)", id);
        int hojasEliminadas = jdbc.update("CALL sp_th_hoja_eliminar(?)", id);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("deleted", hojasEliminadas > 0);
        out.put("id", id);
        out.put("detallesEliminados", detallesEliminados);
        return out;
    }
}