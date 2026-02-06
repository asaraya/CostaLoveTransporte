// src/main/java/com/cargosfsr/inventario/web/ReportesControllerExtra.java
package com.cargosfsr.inventario.controllers;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

@RestController
@RequestMapping("/reportes")
public class ReportesControllerExtra {

    @PersistenceContext
    private EntityManager em;

    // Helper genérico
    @SuppressWarnings("unchecked")
    private List<Object[]> callPorEstado(String estado, String tipoFecha, Instant desde, Instant hasta) {
        return em.createNativeQuery("CALL sp_paquetes_por_estado(?, ?, ?, ?)")
                 .setParameter(1, estado)
                 .setParameter(2, tipoFecha) // usamos 'CAMBIO' u otro => cae en last_state_change_at
                 .setParameter(3, (desde != null ? Timestamp.from(desde) : null))
                 .setParameter(4, (hasta != null ? Timestamp.from(hasta) : null))
                 .getResultList();
    }

    // Devuelve el mismo shape que tus otros /reportes/* (según tu mapeo de ResultSet).
    // Si ya tienes un RowMapper/DTO utilízalo aquí; este ejemplo devuelve la vista cruda.
    @GetMapping("/push")
    public List<?> reportePush(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant hasta) {
        return callPorEstado("PUSH", "CAMBIO", desde, hasta);
    }

    @GetMapping("/almacenaje")
    public List<?> reporteAlmacenaje(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant hasta) {
        return callPorEstado("ALMACENAJE", "CAMBIO", desde, hasta);
    }
}
