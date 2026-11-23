package com.cargosfsr.inventario.mobile;

import java.util.Map;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@Order(0)
@RestControllerAdvice(assignableTypes = MobileController.class)
public class MobileErrors {
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> status(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("message",
                error.getReason() == null ? "No se pudo completar la solicitud" : error.getReason()));
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<Map<String, String>> duplicate(DuplicateKeyException error) {
        return ResponseEntity.status(409).body(Map.of("message", "Ya existe una prueba de entrega para este paquete u operación"));
    }
}
