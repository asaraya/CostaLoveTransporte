package com.cargosfsr.inventario.controllers;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cargosfsr.inventario.model.Saco;
import com.cargosfsr.inventario.repository.SacoRepository;
import com.cargosfsr.inventario.services.RegistroService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

@RestController
@RequestMapping("/api/sacos")
public class SacoController {

    private final SacoRepository sacos;
    private final RegistroService registro;

    public SacoController(SacoRepository sacos, RegistroService registro) {
        this.sacos = sacos;
        this.registro = registro;
    }
    

    // =======================
    // Crear Saco (idempotente)
    // Body: { marchamo, defaultDistritoNombre? }
    // =======================
    @PostMapping
    public ResponseEntity<Saco> crear(@Valid @RequestBody CrearSacoReq req) {
        Saco s = registro.crearSaco(req.marchamo, req.defaultDistritoNombre);
        return ResponseEntity.status(HttpStatus.CREATED).body(s);
    }

    @GetMapping("/{marchamo}/exists")
    public Map<String, Boolean> exists(@PathVariable String marchamo) {
        boolean ok = sacos.findByMarchamo(marchamo).isPresent();
        return Map.of("exists", ok);
    }

    @DeleteMapping("/{marchamo}")
    public ResponseEntity<?> eliminarVacio(@PathVariable String marchamo) {
        try {
            registro.eliminarSacoVacio(marchamo);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", e.getMessage()));
        }
    }

    public static class CrearSacoReq {
        @NotBlank
        public String marchamo;
        public String defaultDistritoNombre;
    }
}
