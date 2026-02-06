package com.cargosfsr.inventario.controllers;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.cargosfsr.inventario.services.ConsultasService;

@RestController
@RequestMapping("/api/reportes")
public class ReportesController {

    private final ConsultasService consultas;
    public ReportesController(ConsultasService c){ this.consultas = c; }

    @GetMapping("/diario")
    public Map<String,Object> diario(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fecha,
            @RequestParam(value = "flat", required = false, defaultValue = "false") boolean flat
    ){
        return flat ? consultas.reporteDiarioFlat(fecha)
                    : consultas.reporteDiario(fecha);
    }

    @GetMapping("/entregados")
    public List<Map<String,Object>> repEntregados(
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) Instant desde,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) Instant hasta,
            @RequestParam(required=false) String marchamo
    ){
        return consultas.entregados(desde, hasta, marchamo);
    }

    @GetMapping("/devolucion")
    public List<Map<String,Object>> repDevolucion(
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) Instant desde,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) Instant hasta,
            @RequestParam(required=false) String marchamo,
            @RequestParam(required=false) String subtipo
    ){
        return consultas.devolucion(desde, hasta, marchamo, subtipo);
    }
}
