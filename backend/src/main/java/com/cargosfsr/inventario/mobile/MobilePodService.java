package com.cargosfsr.inventario.mobile;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import com.cargosfsr.inventario.model.Usuario;
import com.cargosfsr.inventario.model.enums.PaqueteEstado;
import com.cargosfsr.inventario.repository.PaqueteRepository;
import com.cargosfsr.inventario.services.EstadoService;

@Service
public class MobilePodService {
    private final JdbcTemplate jdbc;
    private final PaqueteRepository paquetes;
    private final EstadoService estados;

    public MobilePodService(JdbcTemplate jdbc, PaqueteRepository paquetes, EstadoService estados) {
        this.jdbc = jdbc;
        this.paquetes = paquetes;
        this.estados = estados;
    }

    public Map<String, Object> lookup(String tracking) {
        var rows = jdbc.queryForList("CALL sp_th_mobile_package(?)", normalize(tracking));
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tracking no encontrado en esta sucursal");
        return rows.get(0);
    }

    @Transactional
    public Map<String, Object> deliver(Usuario user, String operationId, String tracking,
            Instant capturedAt, String version, MultipartFile parcel, MultipartFile recipient) throws IOException {
        return deliverPhotos(user, operationId, tracking, capturedAt, version, photo(parcel), photo(recipient));
    }

    @Transactional
    public Map<String, Object> deliverSingle(Usuario user, String operationId, String tracking,
            Instant capturedAt, String version, MultipartFile proof) throws IOException {
        return deliverPhotos(user, operationId, tracking, capturedAt, version, photo(proof), null);
    }

    private Map<String, Object> deliverPhotos(Usuario user, String operationId, String tracking,
            Instant capturedAt, String version, byte[] parcelBytes, byte[] recipientBytes) {
        UUID.fromString(operationId);
        String normalized = normalize(tracking);
        if (capturedAt == null || capturedAt.isAfter(Instant.now().plusSeconds(300))) {
            throw new IllegalArgumentException("Revise la fecha y hora del teléfono");
        }
        String fingerprint = MobileAuth.hash(normalized + "\n" + capturedAt + "\n" + version + "\n"
                + MobileAuth.hash(parcelBytes) + "\n" + (recipientBytes == null ? "SINGLE" : MobileAuth.hash(recipientBytes)));

        // El bloqueo del paquete serializa entregas concurrentes desde móvil y web.
        var paquete = paquetes.findByTrackingCodeForUpdate(normalized).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tracking no encontrado"));
        var previous = jdbc.queryForList("CALL sp_th_mobile_pod_operation(?)", operationId);
        if (!previous.isEmpty()) {
            var pod = previous.get(0);
            if (((Number) pod.get("usuario_id")).longValue() != user.getId()
                    || !fingerprint.equals(pod.get("request_hash"))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "El identificador pertenece a otra entrega");
            }
            return receipt(operationId, normalized);
        }
        var current = lookup(normalized);
        if (!jdbc.queryForList("CALL sp_th_mobile_pod_package(?)", paquete.getId()).isEmpty()
                || paquete.getEstado() == PaqueteEstado.PRUEBA_DE_ENTREGA
                || !java.util.Objects.equals(current.get("version"), version)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "El paquete cambió en el sistema. Consulte la oficina antes de reenviar este POD");
        }
        estados.entregarDesdeMovil(normalized, capturedAt, user);
        jdbc.update("CALL sp_th_mobile_pod_create(?, ?, ?, ?, ?, ?, ?, ?, ?)",
                operationId, paquete.getId(), normalized, user.getId(), user.getFullName(),
                LocalDateTime.ofInstant(capturedAt, ZoneOffset.UTC).toString(), fingerprint,
                parcelBytes, recipientBytes);
        return receipt(operationId, normalized);
    }

    public List<Map<String, Object>> report(Usuario user, java.time.LocalDate from, java.time.LocalDate to) {
        if (from.isAfter(to) || java.time.temporal.ChronoUnit.DAYS.between(from, to) > 366) {
            throw new IllegalArgumentException("Seleccione un rango de hasta 366 días");
        }
        return jdbc.queryForList("CALL sp_th_mobile_pod_report(?, ?, ?)", user.getId(), from, to.plusDays(1));
    }

    private static Map<String, Object> receipt(String id, String tracking) {
        return Map.of("operationId", id, "tracking", tracking, "status", "CONFIRMED", "origin", "APLICACION_MOVIL");
    }

    private static String normalize(String tracking) {
        String value = tracking == null ? "" : tracking.trim().toUpperCase(Locale.ROOT);
        if (!value.matches("(?:HZCR|CR|LM)[A-Z0-9]{1,96}")) throw new IllegalArgumentException("Tracking inválido");
        return value;
    }

    private static byte[] photo(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty() || file.getSize() > 3 * 1024 * 1024) {
            throw new IllegalArgumentException("Cada foto debe ocupar entre 1 byte y 3 MB");
        }
        byte[] bytes = file.getBytes();
        if (bytes.length < 3 || bytes[0] != (byte) 0xff || bytes[1] != (byte) 0xd8 || bytes[2] != (byte) 0xff) {
            throw new IllegalArgumentException("La cámara debe enviar fotografías JPEG");
        }
        return bytes;
    }
}
