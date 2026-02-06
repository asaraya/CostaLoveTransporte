package com.cargosfsr.inventario.auth;

import java.util.List;
import java.util.Map;

import org.springframework.dao.DuplicateKeyException;
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
    public List<Map<String, Object>> listUsers() {
        return jdbc.queryForList(
            "SELECT id, username, full_name, role, active, created_at " +
            "FROM usuarios ORDER BY id DESC"
        );
    }

    @PostMapping("/users")
    public Map<String, Object> createUser(@RequestBody CreateUserReq req) {
        String username = normUsername(req.username);
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
    public Map<String, Object> deleteUser(@PathVariable String username) {
        String u = normUsername(username);
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

    // ---------- Distritos ----------
    // POST /api/admin/distritos  { "nombre": "Roxana" }
    @PostMapping("/distritos")
    public Map<String, Object> addDistrito(@RequestBody AddDistritoReq req) {
        String nombre = normDistrito(req.nombre);
        if (!StringUtils.hasText(nombre) || nombre.length() > 100) {
            throw new IllegalArgumentException("Nombre de distrito inválido");
        }

        // Si existe (activo o inactivo), lo manejamos:
        try {
            Map<String, Object> existing = jdbc.queryForMap(
                "SELECT id, nombre, activo FROM distritos WHERE nombre = ? LIMIT 1",
                nombre
            );

            boolean activo = toBool(existing.get("activo"));
            if (activo) {
                return Map.of("ok", false, "message", "El distrito ya existe", "nombre", existing.get("nombre"));
            }

            Long id = toLong(existing.get("id"));
            int updated = jdbc.update("UPDATE distritos SET activo = 1 WHERE id = ?", id);
            return Map.of(
                "ok", updated > 0,
                "reactivado", true,
                "id", id,
                "nombre", existing.get("nombre")
            );

        } catch (EmptyResultDataAccessException ex) {
            // No existe, insertamos
            try {
                jdbc.update("INSERT INTO distritos (nombre) VALUES (?)", nombre);
                Long id = jdbc.queryForObject(
                    "SELECT id FROM distritos WHERE nombre=? LIMIT 1",
                    Long.class,
                    nombre
                );
                return Map.of("ok", true, "id", id, "nombre", nombre);
            } catch (DuplicateKeyException dk) {
                // Por collation/unique, por si entró una carrera
                return Map.of("ok", false, "message", "El distrito ya existe", "nombre", nombre);
            }
        }
    }

    // DELETE /api/admin/distritos/{nombre}
    // Nota: si hay paquetes asociados, NO se borra físicamente (FK), se desactiva (activo=0).
    @DeleteMapping("/distritos/{nombre}")
    public Map<String, Object> deleteDistrito(@PathVariable String nombre) {
        String n = normDistrito(nombre);
        if (!StringUtils.hasText(n) || n.length() > 100) {
            throw new IllegalArgumentException("Nombre de distrito inválido");
        }

        Long id;
        Integer activo;
        String nombreDb;
        try {
            Map<String, Object> row = jdbc.queryForMap(
                "SELECT id, nombre, activo FROM distritos WHERE nombre=? LIMIT 1",
                n
            );
            id = toLong(row.get("id"));
            activo = (row.get("activo") == null) ? 1 : Integer.valueOf(String.valueOf(row.get("activo")));
            nombreDb = String.valueOf(row.get("nombre"));
        } catch (EmptyResultDataAccessException ex) {
            return Map.of("ok", false, "message", "Distrito no existe");
        }

        Integer usados = jdbc.queryForObject(
            "SELECT COUNT(*) FROM paquetes WHERE distrito_id=?",
            Integer.class,
            id
        );
        int count = (usados == null) ? 0 : usados;

        if (count > 0) {
            // soft-delete
            int upd = jdbc.update("UPDATE distritos SET activo=0 WHERE id=?", id);
            return Map.of(
                "ok", upd > 0,
                "deleted", false,
                "desactivado", true,
                "id", id,
                "nombre", nombreDb,
                "paquetes_asociados", count,
                "message", "Distrito tiene paquetes asociados: se desactivó (activo=0)."
            );
        }

        // sin paquetes: se puede borrar
        int del = jdbc.update("DELETE FROM distritos WHERE id=?", id);
        return Map.of(
            "ok", del > 0,
            "deleted", true,
            "id", id,
            "nombre", nombreDb,
            "prev_activo", activo
        );
    }

    // (Opcional útil) listar distritos desde admin (incluye inactivos)
    @GetMapping("/distritos")
    public List<Map<String, Object>> listDistritosAdmin() {
        return jdbc.queryForList(
            "SELECT id, nombre, activo, created_at FROM distritos ORDER BY nombre ASC"
        );
    }

    // ---------- DTOs ----------
    public static class CreateUserReq {
        public String username;
        public String fullName;
        public String password;
        public String role; // USER | ADMIN
    }

    public static class AddDistritoReq {
        public String nombre;
    }

    // ---------- Helpers ----------
    private String normUsername(String s) {
        return s == null ? null : s.trim().toLowerCase();
    }

    private String normDistrito(String s) {
        // no forzamos lowercase para conservar el display (la collation ya es case-insensitive)
        return s == null ? null : s.trim();
    }

    private boolean toBool(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        String s = String.valueOf(v).trim();
        return "1".equals(s) || "true".equalsIgnoreCase(s) || "y".equalsIgnoreCase(s) || "yes".equalsIgnoreCase(s);
    }

    private Long toLong(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        return Long.valueOf(String.valueOf(v));
    }
}
