package com.cargosfsr.inventario.auth;

import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.cargosfsr.inventario.model.Usuario;
import com.cargosfsr.inventario.repository.UsuarioRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;

@Service
public class UsuariosService {

    private final UsuarioRepository repo;

    @PersistenceContext
    private EntityManager em;

    public UsuariosService(UsuarioRepository repo) {
        this.repo = repo;
    }

    public Optional<Usuario> findByUsername(String username) {
        return repo.findByUsername(username);
    }

    @Transactional
    public UsuarioDto crearUsuario(String username, String fullName, String passwordHash, String role) {
        if (repo.existsByUsername(username)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "El usuario ya está registrado");
        }

        try {
            em.createNativeQuery("CALL sp_crear_usuario(?,?,?,?)")
              .setParameter(1, username)
              .setParameter(2, fullName)
              .setParameter(3, passwordHash)
              .setParameter(4, role == null ? "USER" : role)
              .executeUpdate();
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                "No se pudo crear el usuario: " + e.getMessage());
        }

        var u = repo.findByUsername(username).orElseThrow(
            () -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                "No se pudo leer el usuario recién creado")
        );

        return new UsuarioDto(u.getId(), u.getUsername(), u.getFullName(), u.getRole());
    }
}
