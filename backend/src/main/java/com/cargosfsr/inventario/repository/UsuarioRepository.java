package com.cargosfsr.inventario.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.cargosfsr.inventario.model.Usuario;

public interface UsuarioRepository extends JpaRepository<Usuario, Long> {
    Optional<Usuario> findByUsername(String username);
    boolean existsByUsername(String username);

    // Se mantiene por compat si lo usas en otro lado
    List<Usuario> findByRolAndActiveTrueOrderByFullNameAsc(String rol);

    // NUEVO: trae activos cuyo rol operativo (rol) O rol de login (role) sea uno de los indicados
    @Query("""
           SELECT u
           FROM Usuario u
           WHERE u.active = true
             AND (UPPER(u.rol) IN :roles OR UPPER(u.role) IN :roles)
           ORDER BY u.fullName ASC
           """)
    List<Usuario> findActivosPorRoles(@Param("roles") List<String> roles);
}
