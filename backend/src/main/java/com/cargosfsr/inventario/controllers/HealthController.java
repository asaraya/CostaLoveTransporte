package com.cargosfsr.inventario.controllers;

import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {
    private final JdbcTemplate jdbc;
    public HealthController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping("/health")
    public Map<String, String> health() {
        jdbc.queryForObject("CALL sp_th_usuarios_contar()", Long.class);
        return Map.of("status", "ok");
    }
}
