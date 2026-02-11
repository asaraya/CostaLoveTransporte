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
             AND (TRIM(UPPER(u.rol)) IN :roles OR TRIM(UPPER(u.role)) IN :roles)
           ORDER BY u.fullName ASC
           """)
    List<Usuario> findActivosPorRoles(@Param("roles") List<String> roles);

    /**
     * Mensajería operativa:
     * - Acepta MENSAJERO
     * - Acepta cualquier variante que empiece con TRANSPORTISTA (p.ej. "TRANSPORTISTA.", "TRANSPORTISTA LOCAL")
     *
     * Nota: se busca tanto en columna "rol" (operativo) como en "role" (login/permisos),
     * porque en tu BD coexisten ambas.
     */
    @Query("""
            SELECT u
            FROM Usuario u
            WHERE u.active = true
              AND (
                    TRIM(UPPER(u.rol)) = 'MENSAJERO'
                 OR TRIM(UPPER(u.role)) = 'MENSAJERO'
                 OR TRIM(UPPER(u.rol)) LIKE 'TRANSPORTISTA%'
                 OR TRIM(UPPER(u.role)) LIKE 'TRANSPORTISTA%'
              )
            ORDER BY u.fullName ASC
           """)
    List<Usuario> findMensajeriaActiva();
}
