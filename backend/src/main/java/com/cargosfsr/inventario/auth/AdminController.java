package com.cargosfsr.inventario.auth;

import java.util.List;
import java.util.Map;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final JdbcTemplate jdbc;
    private final BCryptPasswordEncoder bcrypt;

    public AdminController(JdbcTemplate jdbc, BCryptPasswordEncoder bcrypt) {
        this.jdbc = jdbc;
        this.bcrypt = bcrypt;
    }

    // ---------- Usuarios ----------
    @GetMapping("/users")
    public List<Map<String,Object>> listUsers() {
        return jdbc.queryForList(
            "SELECT id, username, full_name, role, active, created_at FROM usuarios ORDER BY id DESC"
        );
    }

    @PostMapping("/users")
    public Map<String,Object> createUser(@RequestBody CreateUserReq req) {
        String username = norm(req.username);
        String fullName = req.fullName == null ? null : req.fullName.trim();
        String role     = (req.role == null || req.role.isBlank()) ? "USER" : req.role.trim().toUpperCase();

        if (!StringUtils.hasText(username) || username.length() < 3 || !username.matches("^[a-z0-9._-]{3,60}$")) {
            throw new IllegalArgumentException("Usuario inválido");
        }
        if (!StringUtils.hasText(fullName)) throw new IllegalArgumentException("Nombre inválido");
        if (!StringUtils.hasText(req.password) || req.password.length() < 6) throw new IllegalArgumentException("Contraseña muy corta");
        if (!role.equals("USER") && !role.equals("ADMIN")) throw new IllegalArgumentException("Rol inválido");

        String hash = bcrypt.encode(req.password);
        jdbc.update("CALL sp_crear_usuario(?,?,?,?)", username, fullName, hash, role);
        return Map.of("ok", true, "username", username, "role", role);
    }

    @DeleteMapping("/users/{username}")
    public Map<String,Object> deleteUser(@PathVariable String username) {
        String u = norm(username);
        String role;
        try {
            role = jdbc.queryForObject("SELECT role FROM usuarios WHERE username=?", String.class, u);
        } catch (EmptyResultDataAccessException ex) {
            return Map.of("ok", false, "message", "Usuario no existe");
        }
        if ("ADMIN".equalsIgnoreCase(role)) {
            return Map.of("ok", false, "message", "No se puede eliminar un usuario ADMIN");
        }
        int rows = jdbc.update("DELETE FROM usuarios WHERE username=?", u);
        return Map.of("ok", rows > 0);
    }

    // ---------- Muebles ----------
    @PostMapping("/muebles")
    public List<Map<String,Object>> agregarMueble(@RequestBody AddMuebleReq req) {
        if (req.mueble == null || req.mueble <= 0) throw new IllegalArgumentException("Número de mueble inválido");
        if (req.estanterias == null || req.estanterias <= 0) throw new IllegalArgumentException("Número de estanterías inválido");
        return jdbc.queryForList("CALL sp_agregar_mueble(?,?)", req.mueble, req.estanterias);
    }

    // NUEVO: eliminar mueble + todas sus estanterías
    // Mueve previamente los paquetes/sacos a la ubicación 'PENDIENTE' para no romper FKs.
    @DeleteMapping("/muebles/{mueble}")
    public Map<String,Object> eliminarMueble(@PathVariable Integer mueble) {
        if (mueble == null || mueble <= 0) throw new IllegalArgumentException("Número de mueble inválido");

        // id de la ubicación PENDIENTE (se crea si no existe)
        Long pendId = jdbc.queryForObject("SELECT id FROM ubicacion WHERE codigo='PENDIENTE' LIMIT 1",
                Long.class);
        if (pendId == null) {
            jdbc.update("INSERT INTO ubicacion(tipo, codigo, activo) VALUES ('MUEBLE','PENDIENTE',1)");
            pendId = jdbc.queryForObject("SELECT id FROM ubicacion WHERE codigo='PENDIENTE' LIMIT 1",
                    Long.class);
        }

        // ¿existen ubicaciones de ese mueble?
        Integer exists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ubicacion WHERE tipo='MUEBLE' AND mueble_num=?",
                Integer.class, mueble);
        if (exists == null || exists == 0) {
            return Map.of("ok", false, "message", "El mueble no existe");
        }

        // mover paquetes/sacos a 'PENDIENTE'
        int movedPaq = jdbc.update(
            "UPDATE paquetes SET ubicacion_id=? " +
            "WHERE ubicacion_id IN (SELECT id FROM ubicacion WHERE tipo='MUEBLE' AND mueble_num=?)",
            pendId, mueble);

        int movedSacos = jdbc.update(
            "UPDATE sacos SET default_ubicacion_id=? " +
            "WHERE default_ubicacion_id IN (SELECT id FROM ubicacion WHERE tipo='MUEBLE' AND mueble_num=?)",
            pendId, mueble);

        // eliminar ubicaciones del mueble
        int deletedUbics = jdbc.update(
            "DELETE FROM ubicacion WHERE tipo='MUEBLE' AND mueble_num=?", mueble);

        return Map.of(
            "ok", true,
            "paquetes_movidos", movedPaq,
            "sacos_movidos", movedSacos,
            "ubicaciones_eliminadas", deletedUbics
        );
    }

    // ---------- DTOs ----------
    public static class CreateUserReq {
        public String username;
        public String fullName;
        public String password;
        public String role; // USER | ADMIN
    }
    public static class AddMuebleReq {
        public Integer mueble;
        public Integer estanterias;
    }

    private String norm(String s) { return s == null ? null : s.trim().toLowerCase(); }
}
