package com.cargosfsr.inventario.mobile;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import com.cargosfsr.inventario.model.Usuario;
import com.cargosfsr.inventario.repository.UsuarioRepository;

@Service
public class MobileAuth {
    private final JdbcTemplate jdbc;
    private final UsuarioRepository usuarios;
    private final BCryptPasswordEncoder encoder;
    private final SecureRandom random = new SecureRandom();

    public MobileAuth(JdbcTemplate jdbc, UsuarioRepository usuarios, BCryptPasswordEncoder encoder) {
        this.jdbc = jdbc;
        this.usuarios = usuarios;
        this.encoder = encoder;
    }

    public Map<String, Object> login(String username, String password) {
        if (username == null || password == null || username.length() > 60 || password.length() > 200) {
            throw unauthorized();
        }
        var user = usuarios.findByUsername(username.trim().toLowerCase(Locale.ROOT)).orElseThrow(MobileAuth::unauthorized);
        if (!encoder.matches(password, user.getPassword())) throw unauthorized();
        requireCourier(user);
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        long expires = Instant.now().plusSeconds(30L * 24 * 3600).getEpochSecond();
        jdbc.update("CALL sp_th_mobile_session_create(?, ?, ?)", hash(token), user.getId(), expires);
        return Map.of("token", token, "expiresAt", expires, "user", profile(user));
    }

    public Usuario authenticate(String header) {
        String token = bearer(header);
        var ids = jdbc.queryForList("CALL sp_th_mobile_session_user(?, ?)", Long.class,
                hash(token), Instant.now().getEpochSecond());
        if (ids.isEmpty()) throw unauthorized();
        var user = usuarios.findById(ids.get(0)).orElseThrow(MobileAuth::unauthorized);
        requireCourier(user);
        return user;
    }

    public void logout(String header) {
        jdbc.update("CALL sp_th_mobile_session_revoke(?)", hash(bearer(header)));
    }

    public static Map<String, Object> profile(Usuario user) {
        return Map.of("id", user.getId(), "username", user.getUsername(), "name", user.getFullName());
    }

    private static String bearer(String header) {
        if (header == null || !header.matches("Bearer [A-Za-z0-9_-]{43}")) throw unauthorized();
        return header.substring(7);
    }

    private static void requireCourier(Usuario user) {
        if (!Boolean.TRUE.equals(user.getActive()) || !(courierRole(user.getRol()) || courierRole(user.getRole()))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Se requiere un usuario mensajero activo de esta sucursal");
        }
    }

    private static boolean courierRole(String value) {
        String role = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        return "MENSAJERO".equals(role) || role.startsWith("TRANSPORTISTA");
    }

    public static String hash(String value) {
        return hash(value.getBytes(StandardCharsets.UTF_8));
    }

    public static String hash(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    private static ResponseStatusException unauthorized() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Inicie sesión con su usuario de esta sucursal");
    }
}
