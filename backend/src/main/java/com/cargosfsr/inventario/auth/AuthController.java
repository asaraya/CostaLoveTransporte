package com.cargosfsr.inventario.auth;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.cargosfsr.inventario.model.Usuario;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UsuariosService usuarios;
    private final BCryptPasswordEncoder bcrypt;

    public AuthController(UsuariosService usuarios, BCryptPasswordEncoder bcrypt) {
        this.usuarios = usuarios;
        this.bcrypt = bcrypt;
    }

    @PostMapping("/register")
    public UsuarioDto register(@Valid @RequestBody RegisterRequest req) {
        String username = req.username() == null ? null : req.username().trim().toLowerCase();
        String fullName = req.fullName() == null ? null : req.fullName().trim();

        if (!StringUtils.hasText(username) || username.length() < 3) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Usuario inválido");
        }
        if (!username.matches("^[a-z0-9._-]{3,60}$")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Usuario inválido (solo letras/números/._-)");
        }
        if (!StringUtils.hasText(fullName)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nombre inválido");
        }
        if (!StringUtils.hasText(req.password()) || req.password().length() < 6) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Contraseña muy corta");
        }

        String hash = bcrypt.encode(req.password());
        return usuarios.crearUsuario(username, fullName, hash, "USER");
    }

    @PostMapping("/login")
    public UsuarioDto login(@Valid @RequestBody LoginRequest req, HttpServletRequest request) {
        String username = req.username() == null ? null : req.username().trim().toLowerCase();
        if (!StringUtils.hasText(username) || !StringUtils.hasText(req.password())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Usuario/contraseña requeridos");
        }

        Usuario u = usuarios.findByUsername(username)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Usuario o contraseña inválidos"));

        if (!bcrypt.matches(req.password(), u.getPassword())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Usuario o contraseña inválidos");
        }

        HttpSession session = request.getSession(true);
        setSessionOk(session, u.getUsername(), u.getFullName(), u.getRole());
        // >>> CLAVE: el interceptor valida este atributo
        session.setAttribute("AUTH_USER_ID", u.getId());

        return new UsuarioDto(u.getId(), u.getUsername(), u.getFullName(), u.getRole());
    }

    @GetMapping("/me")
    public Map<String, Object> me(HttpServletRequest request) {
        HttpSession s = request.getSession(false);
        if (s == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No autenticado");

        Object uid = s.getAttribute("AUTH_USER_ID");
        String username = (String) s.getAttribute("AUTH_USER_USERNAME");
        if (uid == null || username == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No autenticado");
        }

        // Sincroniza rol con BD si cambió
        String roleSession = (String) s.getAttribute("AUTH_USER_ROLE");
        String roleDb = usuarios.findByUsername(username)
                .map(Usuario::getRole)
                .orElse(roleSession);
        if (roleDb != null && !roleDb.equals(roleSession)) {
            s.setAttribute("AUTH_USER_ROLE", roleDb);
        }

        return Map.of(
            "id", uid,
            "username", username,
            "name", s.getAttribute("AUTH_USER_NAME"),
            "role", s.getAttribute("AUTH_USER_ROLE")
        );
    }


    @PostMapping("/logout")
    public Map<String, Object> logout(HttpServletRequest request) {
        HttpSession s = request.getSession(false);
        if (s != null) s.invalidate();
        return Map.of("ok", true);
    }

    private static void setSessionOk(HttpSession session, String username, String name, String role) {
        session.setAttribute("AUTH", true);
        session.setAttribute("AUTH_USER_USERNAME", username);
        session.setAttribute("AUTH_USER_NAME", name);
        session.setAttribute("AUTH_USER_ROLE", role);
    }
}
