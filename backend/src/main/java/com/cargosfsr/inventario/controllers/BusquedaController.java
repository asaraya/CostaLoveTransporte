package com.cargosfsr.inventario.controllers;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.cargosfsr.inventario.services.ConsultasService;

@RestController
@RequestMapping("/api/busqueda")
public class BusquedaController {

    private final ConsultasService consultas;

    public BusquedaController(ConsultasService consultas) {
        this.consultas = consultas;
    }

    /* ==========================
     * INVENTARIO PAGINADO + COUNT
     * ========================== */

    /**
     * estado:
     * NO_ENTREGADO_CONSIGNATARIO_DISPONIBLE (default) |
     * ENTREGADO_A_TRANSPORTISTA_LOCAL |
     * ENTREGADO_A_TRANSPORTISTA_LOCAL_2DO_INTENTO |
     * NO_ENTREGABLE |
     * TODOS
     */
    @GetMapping("/inventario")
    public List<Map<String, Object>> inventario(
            @RequestParam(name = "estado", defaultValue = "NO_ENTREGADO_CONSIGNATARIO_DISPONIBLE") String estado,
            @RequestParam(name = "limit", defaultValue = "20") int limit,
            @RequestParam(name = "offset", defaultValue = "0") int offset) {
        return consultas.inventarioPaginado(estado, limit, offset);
    }

    @GetMapping("/inventario/count")
    public Map<String, Object> inventarioCount(
            @RequestParam(name = "estado", defaultValue = "NO_ENTREGADO_CONSIGNATARIO_DISPONIBLE") String estado) {
        long total = consultas.countInventario(estado);
        return Collections.singletonMap("total", total);
    }

    /* ==========================
     * FILTROS + COUNTS
     * ========================== */

    /** Por estado + tipoFecha + [desde, hasta] */
    @GetMapping("/estado")
    public List<Map<String, Object>> porEstado(
            @RequestParam String estado,
            @RequestParam(name = "tipoFecha", defaultValue = "CAMBIO") String tipoFecha,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant hasta) {
        return consultas.porEstado(estado, tipoFecha, desde, hasta);
    }

    /** Por distrito (nombre) */
    @GetMapping("/distrito/{nombre}")
    public List<Map<String, Object>> porDistrito(
            @PathVariable String nombre,
            @RequestParam(name = "tipoFecha", defaultValue = "CAMBIO") String tipoFecha,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant hasta,
            @RequestParam(required = false) String estado
    ) {
        return consultas.porDistrito(nombre, tipoFecha, desde, hasta, estado);
    }

    @GetMapping("/distrito/{nombre}/count")
    public Map<String, Object> countDistrito(@PathVariable String nombre) {
        long total = consultas.countPorDistrito(nombre);
        return Collections.singletonMap("total", total);
    }

    /** Por nombre: like=1 contiene (default), like=0 exacta */
    @GetMapping("/nombre")
    public List<Map<String, Object>> porNombre(
            @RequestParam(name = "q") String q,
            @RequestParam(name = "like", defaultValue = "1") int like) {
        return consultas.porNombre(q, like);
    }

    @GetMapping("/nombre/count")
    public Map<String, Object> countNombre(
            @RequestParam(name = "q") String q,
            @RequestParam(name = "like", defaultValue = "1") int like) {
        long total = consultas.countPorNombre(q, like);
        return Collections.singletonMap("total", total);
    }

    /** Por dirección: like=1 contiene (default), like=0 exacta */
    @GetMapping("/direccion")
    public List<Map<String, Object>> porDireccion(
            @RequestParam(name = "q") String q,
            @RequestParam(name = "like", defaultValue = "1") int like) {
        return consultas.porDireccion(q, like);
    }

    @GetMapping("/direccion/count")
    public Map<String, Object> countDireccion(
            @RequestParam(name = "q") String q,
            @RequestParam(name = "like", defaultValue = "1") int like) {
        long total = consultas.countPorDireccion(q, like);
        return Collections.singletonMap("total", total);
    }

    /** Por tracking: like=1 contiene, like=0 exacta */
    @GetMapping("/tracking")
    public List<Map<String, Object>> porTracking(
            @RequestParam(name = "q") String q,
            @RequestParam(name = "like", defaultValue = "0") int like) {
        return consultas.porTracking(q, like);
    }

    @GetMapping("/tracking/count")
    public Map<String, Object> countTracking(
            @RequestParam(name = "q") String q,
            @RequestParam(name = "like", defaultValue = "0") int like) {
        long total = consultas.countPorTracking(q, like);
        return Collections.singletonMap("total", total);
    }

    /** Por patrón de tracking (LIKE completo, debe incluir %/_ si se requiere) */
    @GetMapping("/tracking-like")
    public List<Map<String, Object>> porTrackingLike(@RequestParam(name = "patron") String patron) {
        return consultas.porTrackingLike(patron);
    }

    /** Solo por fecha sin estado: tipoFecha RECEPCION/ENTREGA/DEVOLUCION/CAMBIO */
    @GetMapping("/fecha")
    public List<Map<String, Object>> porFecha(
            @RequestParam(name = "tipoFecha", defaultValue = "CAMBIO") String tipoFecha,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant hasta) {
        return consultas.porFecha(tipoFecha, desde, hasta);
    }

    /** Por marchamo (opcional estado/tipoFecha/fechas) */
    @GetMapping("/marchamo/{marchamo}")
    public List<Map<String, Object>> porMarchamo(
            @PathVariable String marchamo,
            @RequestParam(required = false) String estado,
            @RequestParam(name = "tipoFecha", defaultValue = "CAMBIO") String tipoFecha,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant hasta) {
        return consultas.porMarchamo(marchamo, estado, tipoFecha, desde, hasta);
    }

    @GetMapping("/marchamo/{marchamo}/count")
    public Map<String, Object> countMarchamo(@PathVariable String marchamo) {
        long total = consultas.countPorMarchamo(marchamo);
        return Collections.singletonMap("total", total);
    }

    /** Detalle simple por tracking (vista/SP) */
    @GetMapping("/{tracking}")
    public List<Map<String, Object>> detalle(@PathVariable String tracking) {
        return consultas.detallePorTracking(tracking);
    }

    /** Detalle + historial por tracking (SP RS múltiple) */
    @GetMapping("/{tracking}/detalle")
    public Map<String, Object> detalleCompleto(@PathVariable String tracking) {
        return consultas.detalleCompletoPorTracking(tracking);
    }

    /** Salud */
    @GetMapping("/ping")
    public Map<String, Object> ping() {
        return Collections.singletonMap("ok", true);
    }
}
