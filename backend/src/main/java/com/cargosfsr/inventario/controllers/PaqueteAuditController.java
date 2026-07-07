package com.cargosfsr.inventario.controllers;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cargosfsr.inventario.services.PaqueteAuditService;

@RestController
@RequestMapping("/api/auditoria/paquetes")
public class PaqueteAuditController {

    private final PaqueteAuditService auditService;

    public PaqueteAuditController(PaqueteAuditService auditService) {
        this.auditService = auditService;
    }

    @GetMapping("/{tracking}")
    public Map<String, Object> historial(@PathVariable String tracking) {
        return auditService.historialPorTracking(tracking);
    }
}
