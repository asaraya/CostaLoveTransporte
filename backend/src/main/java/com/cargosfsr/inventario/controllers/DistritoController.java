package com.cargosfsr.inventario.controllers;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/distritos")
public class DistritoController {

    private final JdbcTemplate jdbc;

    public DistritoController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    public List<Map<String,Object>> listar() {
        return jdbc.queryForList(
            "SELECT id, nombre, activo, created_at FROM distritos ORDER BY nombre ASC"
        );
    }
}
