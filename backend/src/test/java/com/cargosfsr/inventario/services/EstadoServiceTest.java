package com.cargosfsr.inventario.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.cargosfsr.inventario.auth.CurrentUser;
import com.cargosfsr.inventario.model.Paquete;
import com.cargosfsr.inventario.model.Usuario;
import com.cargosfsr.inventario.model.enums.*;
import com.cargosfsr.inventario.repository.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

class EstadoServiceTest {
    private final PaqueteRepository paquetes = mock(PaqueteRepository.class);
    private final PaqueteEstadoHistorialRepository historial = mock(PaqueteEstadoHistorialRepository.class);
    private final UsuarioRepository usuarios = mock(UsuarioRepository.class);
    private final PaqueteAuditService audit = mock(PaqueteAuditService.class);
    private final CurrentUser currentUser = mock(CurrentUser.class);
    private final Instant original = Instant.parse("2026-10-01T18:00:00Z");
    private final Instant retry = Instant.parse("2026-10-02T18:00:00Z");
    private EstadoService service;
    private Paquete p;
    private Usuario courier;

    @BeforeEach void setup() {
        service = new EstadoService(paquetes, historial, audit, usuarios, currentUser);
        EntityManager em = mock(EntityManager.class);
        when(em.createNativeQuery(anyString())).thenReturn(mock(Query.class, RETURNS_SELF));
        ReflectionTestUtils.setField(service, "em", em);
        p = new Paquete();
        ReflectionTestUtils.setField(p, "id", 1L);
        p.setTrackingCode("CR123");
        p.setLastStateChangeAt(original);
        when(paquetes.findByTrackingCodeForUpdate("CR123")).thenReturn(Optional.of(p));
        courier = courier(10L);
        when(usuarios.findById(10L)).thenReturn(Optional.of(courier));
    }

    private Usuario courier(long id) {
        Usuario u = new Usuario();
        ReflectionTestUtils.setField(u, "id", id);
        u.setRol("MENSAJERO"); u.setActive(true); u.setFullName("Mensajero " + id);
        return u;
    }

    @Test void repeatedDeliveryDoesNotMoveDateOrCreateMovement() {
        p.setEstado(PaqueteEstado.PRUEBA_DE_ENTREGA); p.setMensajero(courier); p.setDeliveredAt(original);
        var result = service.actualizarEstadoPorTracking(" cr123 ", p.getEstado(), null, "test", false, retry, null, 10L);
        assertEquals(false, result.get("changed"));
        assertEquals(original, p.getDeliveredAt());
        assertEquals(original, p.getLastStateChangeAt());
        verify(paquetes, never()).save(any()); verifyNoInteractions(historial);
    }

    @Test void repeatedReturnDoesNotMoveDate() {
        p.setEstado(PaqueteEstado.NO_ENTREGABLE); p.setReturnedAt(original);
        var result = service.actualizarEstadoPorTracking("CR123", p.getEstado(), null, "test", false, retry, null, null);
        assertEquals(false, result.get("changed"));
        assertEquals(original, p.getReturnedAt()); verifyNoInteractions(historial);
    }

    @Test void correctingReturnSubtypePreservesOriginalReturnDate() {
        p.setEstado(PaqueteEstado.NO_ENTREGABLE); p.setReturnedAt(original);
        var result = service.actualizarEstadoPorTracking("CR123", p.getEstado(), null, "test", false, retry, "VENCIDOS", null);
        assertEquals(true, result.get("subtipo_changed"));
        assertEquals(original, p.getReturnedAt()); verifyNoInteractions(historial);
    }

    @Test void reassigningCourierDoesNotCountAnotherDelivery() {
        p.setEstado(PaqueteEstado.PRUEBA_DE_ENTREGA); p.setDeliveredAt(original); p.setMensajero(courier);
        when(usuarios.findById(11L)).thenReturn(Optional.of(courier(11L)));
        var result = service.actualizarEstadoPorTracking("CR123", p.getEstado(), null, "test", false, retry, null, 11L);
        assertEquals(true, result.get("status_detail_changed"));
        assertEquals(original, p.getDeliveredAt()); verifyNoInteractions(historial);
    }

    @Test void repeatedForcedAvailableDoesNotCreateMovement() {
        p.setEstado(PaqueteEstado.NO_ENTREGADO_CONSIGNATARIO_DISPONIBLE);
        var result = service.actualizarEstadoPorTracking("CR123", p.getEstado(), null, "test", true, retry, null, null);
        assertEquals(false, result.get("changed")); verifyNoInteractions(historial);
    }

    @Test void bulkNormalizesDuplicatesAndCreatesOneRealTransition() {
        var result = service.actualizarEstadoBulk(List.of("CR123", "cr123", " CR123 "),
                PaqueteEstado.PRUEBA_DE_ENTREGA, null, "test", false, retry, null, 10L);
        assertEquals(3, result.get("solicitados")); assertEquals(1, result.get("total"));
        assertEquals(1, result.get("ok")); assertEquals(retry, p.getDeliveredAt());
        verify(historial, times(1)).save(any());
        verify(paquetes, times(1)).findByTrackingCodeForUpdate("CR123");
    }
}
