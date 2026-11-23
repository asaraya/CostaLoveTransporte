package com.cargosfsr.inventario.auth;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.util.StringUtils;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
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
            "CALL sp_th_usuarios_listar()"
        );
    }

    @PostMapping("/users")
    public Map<String, Object> createUser(@RequestBody CreateUserReq req) {
        String username = normUsername(req.username);
        String fullName = req.fullName == null ? null : req.fullName.trim();
        String role     = (req.role == null || req.role.isBlank()) ? "USER" : req.role.trim().toUpperCase(Locale.ROOT);

        if (!StringUtils.hasText(username) || username.length() < 3 || !username.matches("^[a-z0-9._-]{3,60}$")) {
            throw new IllegalArgumentException("Usuario inválido");
        }
        if (!StringUtils.hasText(fullName)) throw new IllegalArgumentException("Nombre inválido");
        if (!StringUtils.hasText(req.password) || req.password.length() < 6) throw new IllegalArgumentException("Contraseña muy corta");

        // Roles soportados por la app (string en BD)
        if (!role.equals("USER") && !role.equals("ADMIN") && !role.equals("TRANSPORTISTA")) {
            throw new IllegalArgumentException("Rol inválido");
        }

        String hash = bcrypt.encode(req.password);
        jdbc.update("CALL sp_crear_usuario(?,?,?,?)", username, fullName, hash, role);
        return Map.of("ok", true, "username", username, "role", role);
    }

    @PostMapping("/mensajeros")
    @Transactional
    public Map<String, Object> createMensajero(@RequestBody CreateMensajeroReq req) {
        String fullName = StringUtils.hasText(req.fullName) ? req.fullName.trim()
                        : (req.nombre == null ? null : req.nombre.trim());
        String username = normUsername(req.username);
        if (username == null || !username.matches("^[a-z0-9._-]{3,60}$")) {
            throw new IllegalArgumentException("Usuario inválido: use de 3 a 60 letras, números, puntos, guiones o guiones bajos");
        }
        if (!StringUtils.hasText(fullName) || fullName.length() > 150) {
            throw new IllegalArgumentException("Nombre inválido");
        }
        validateCourierPassword(req.password);
        try {
            Map<String, Object> row = jdbc.queryForMap("CALL sp_th_mensajero_crear_acceso(?,?,?)",
                    username, fullName, bcrypt.encode(req.password));
            return Map.of("ok", true, "user", row);
        } catch (DuplicateKeyException dk) {
            throw new IllegalArgumentException("Ese usuario ya existe. Elija otro o asigne contraseña al mensajero existente");
        }
    }

    @PostMapping("/mensajeros/{id}/password")
    @Transactional
    public Map<String, Object> setMensajeroPassword(@PathVariable Long id, @RequestBody CourierPasswordReq req) {
        if (id == null || id <= 0) throw new IllegalArgumentException("Mensajero inválido");
        validateCourierPassword(req.password);
        try {
            var row = jdbc.queryForMap("CALL sp_th_mensajero_asignar_clave(?,?)", id, bcrypt.encode(req.password));
            return Map.of("ok", true, "user", row);
        } catch (DataAccessException error) {
            if (error.getMostSpecificCause() instanceof java.sql.SQLException sql && "45000".equals(sql.getSQLState())) {
                throw new IllegalArgumentException("Seleccione un mensajero existente");
            }
            throw error;
        }
    }

    private static void validateCourierPassword(String password) {
        if (!StringUtils.hasText(password) || password.length() < 8) {
            throw new IllegalArgumentException("La contraseña debe tener al menos 8 caracteres");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException("La contraseña supera el máximo de 72 bytes; use una más corta");
        }
    }

    @DeleteMapping("/users/{username}")
    public Map<String, Object> deleteUser(@PathVariable String username) {
        String u = normUsername(username);
        String role;
        try {
            role = jdbc.queryForObject("CALL sp_th_usuario_rol(?)", String.class, u);
        } catch (EmptyResultDataAccessException ex) {
            return Map.of("ok", false, "message", "Usuario no existe");
        }
        if ("ADMIN".equalsIgnoreCase(role)) {
            return Map.of("ok", false, "message", "No se puede eliminar un usuario ADMIN");
        }
        int rows = jdbc.update("CALL sp_th_usuario_eliminar(?)", u);
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

        try {
            Map<String, Object> existing = jdbc.queryForMap(
                "CALL sp_th_distrito_buscar(?)",
                nombre
            );

            boolean activo = toBool(existing.get("activo"));
            if (activo) {
                return Map.of("ok", false, "message", "El distrito ya existe", "nombre", existing.get("nombre"));
            }

            Long id = toLong(existing.get("id"));
            int updated = jdbc.update("CALL sp_th_distrito_reactivar(?)", id);
            return Map.of(
                "ok", updated > 0,
                "reactivado", true,
                "id", id,
                "nombre", existing.get("nombre")
            );

        } catch (EmptyResultDataAccessException ex) {
            try {
                jdbc.update("CALL sp_th_distrito_crear(?)", nombre);
                Long id = jdbc.queryForObject(
                    "CALL sp_th_distrito_id(?)",
                    Long.class,
                    nombre
                );
                return Map.of("ok", true, "id", id, "nombre", nombre);
            } catch (DuplicateKeyException dk) {
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
        boolean activoBool;
        String nombreDb;
        try {
            Map<String, Object> row = jdbc.queryForMap(
                "CALL sp_th_distrito_buscar(?)",
                n
            );
            id = toLong(row.get("id"));
            activoBool = toBool(row.get("activo"));
            nombreDb = String.valueOf(row.get("nombre"));
        } catch (EmptyResultDataAccessException ex) {
            return Map.of("ok", false, "message", "Distrito no existe");
        }

        Long usados = jdbc.queryForObject(
            "CALL sp_th_distrito_contar_paquetes(?)",
            Long.class,
            id
        );
        long count = (usados == null) ? 0 : usados;

        if (count > 0) {
            int upd = jdbc.update("CALL sp_th_distrito_desactivar(?)", id);
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

        int del = jdbc.update("CALL sp_th_distrito_eliminar(?)", id);
        return Map.of(
            "ok", del > 0,
            "deleted", true,
            "id", id,
            "nombre", nombreDb,
            "prev_activo", activoBool
        );
    }

    // (Opcional útil) listar distritos desde admin (incluye inactivos)
    @GetMapping("/distritos")
    public List<Map<String, Object>> listDistritosAdmin() {
        return jdbc.queryForList(
            "CALL sp_th_distritos_listar()"
        );
    }

    // ---------- Muebles / Ubicaciones ----------
    // POST /api/admin/muebles
    @PostMapping("/muebles")
    public Map<String, Object> addMueble(@RequestBody AddMuebleReq req) {
        if (req == null || req.mueble == null || req.mueble <= 0) {
            throw new IllegalArgumentException("Número de mueble inválido");
        }
        if (req.estanterias == null || req.estanterias <= 0) {
            throw new IllegalArgumentException("Número de estanterías inválido");
        }

        for (int est = 1; est <= req.estanterias; est++) {
            String codigo = "M " + req.mueble + "'" + est;

            jdbc.update("CALL sp_th_ubicacion_guardar(?, ?, ?, ?, ?)",
                req.mueble, est, codigo,
                req.mueble, est
            );
        }

        return Map.of(
            "ok", true,
            "mueble", req.mueble,
            "estanterias", req.estanterias
        );
    }

    // DELETE /api/admin/muebles/{mueble}
    @DeleteMapping("/muebles/{mueble}")
    public Map<String, Object> deleteMueble(@PathVariable Integer mueble) {
        if (mueble == null || mueble <= 0) {
            throw new IllegalArgumentException("Número de mueble inválido");
        }

        jdbc.update("CALL sp_th_ubicacion_crear_pendiente()");

        Long pendienteId = jdbc.queryForObject(
            "CALL sp_th_ubicacion_pendiente_id()",
            Long.class
        );

        if (pendienteId == null) {
            throw new IllegalStateException("No se pudo asegurar ubicación PENDIENTE");
        }

        Integer ubicaciones = jdbc.queryForObject(
            "CALL sp_th_mueble_contar(?)",
            Integer.class,
            mueble
        );

        int paquetesMovidos = jdbc.update("CALL sp_th_mueble_reubicar_paquetes(?, ?)",
            pendienteId,
            mueble
        );

        jdbc.update("CALL sp_th_mueble_reubicar_sacos(?, ?)",
            pendienteId,
            mueble
        );

        int ubicacionesEliminadas = jdbc.update(
            "CALL sp_th_mueble_eliminar(?)",
            mueble
        );

        return Map.of(
            "ok", true,
            "mueble", mueble,
            "ubicaciones_encontradas", ubicaciones == null ? 0 : ubicaciones,
            "ubicaciones_eliminadas", ubicacionesEliminadas,
            "paquetes_movidos", paquetesMovidos
        );
    }

    // ---------- DTOs ----------
    public static class AddMuebleReq {
        public Integer mueble;
        public Integer estanterias;
    }
    
    public static class CreateUserReq {
        public String username;
        public String fullName;
        public String password;
        public String role; // USER | ADMIN | TRANSPORTISTA
    }

    public static class CreateMensajeroReq {
        public String username;
        public String password;
        public String fullName; // recomendado
        public String nombre;   // opcional (por compatibilidad con front)
    }

    public static class CourierPasswordReq {
        public String password;
    }

    public static class AddDistritoReq {
        public String nombre;
    }

    // ---------- Helpers ----------
    private String normUsername(String s) {
        return s == null ? null : s.trim().toLowerCase(Locale.ROOT);
    }

    private String normDistrito(String s) {
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
