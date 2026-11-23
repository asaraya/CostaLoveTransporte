package com.cargosfsr.inventario.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.cargosfsr.inventario.model.Usuario;

public interface UsuarioRepository extends JpaRepository<Usuario, Long> {
    Optional<Usuario> findByUsername(String username);
    boolean existsByUsername(String username);

    // Se mantiene por compat si lo usas en otro lado
    List<Usuario> findByRolAndActiveTrueOrderByFullNameAsc(String rol);

    /**
     * Mensajería operativa:
     * - Acepta MENSAJERO
     * - Acepta cualquier variante que empiece con TRANSPORTISTA (p.ej. "TRANSPORTISTA.", "TRANSPORTISTA LOCAL")
     *
     * Nota: se busca tanto en columna "rol" (operativo) como en "role" (login/permisos),
     * porque en tu BD coexisten ambas.
     */
    @Query(value = "CALL sp_th_mensajeria_activa()", nativeQuery = true)
    List<Usuario> findMensajeriaActiva();
}
