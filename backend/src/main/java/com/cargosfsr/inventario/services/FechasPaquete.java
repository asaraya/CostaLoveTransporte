package com.cargosfsr.inventario.services;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/** Añade fechas a resultados de los SP sin cambiar sus vistas ni su cardinalidad. */
public final class FechasPaquete {
    private FechasPaquete() {}

    public static List<Map<String, Object>> completar(JdbcTemplate jdbc, List<Map<String, Object>> rows) {
        var trackings = rows.stream().map(r -> r.get("tracking_code"))
                .filter(java.util.Objects::nonNull).map(Object::toString).distinct().toList();
        if (trackings.isEmpty()) return rows;
        Map<String, Map<String, Object>> fechas = new HashMap<>();
        for (int from = 0; from < trackings.size(); from += 500) {
            var batch = trackings.subList(from, Math.min(from + 500, trackings.size()));
            // Formato explícito: JDBC no debe interpretar DATETIME local como UTC.
            for (var row : jdbc.queryForList("CALL sp_th_paquetes_fechas(?)", ParametrosJson.lista(batch))) {
                fechas.put(row.get("tracking_code").toString(), row);
            }
        }
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (var row : rows) {
            Map<String, Object> copy = new LinkedHashMap<>(row);
            var dates = fechas.get(String.valueOf(row.get("tracking_code")));
            if (dates != null) dates.forEach((key, value) -> {
                if (!key.equals("tracking_code")) copy.put(key, value);
            });
            out.add(copy);
        }
        return out;
    }
}
