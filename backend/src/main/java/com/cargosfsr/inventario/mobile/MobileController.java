package com.cargosfsr.inventario.mobile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import com.cargosfsr.inventario.model.Usuario;

@RestController
@RequestMapping("/api/mobile/v1")
public class MobileController {
    private final MobileAuth auth;
    private final MobilePodService pods;

    public MobileController(MobileAuth auth, MobilePodService pods) { this.auth = auth; this.pods = pods; }

    public record Login(String username, String password) {}
    public record Attempt(String operationId, String tracking, Instant capturedAt, String version) {}

    @PostMapping("/attempts")
    public Map<String, Object> attempt(@RequestAttribute("mobileUser") Usuario user, @RequestBody Attempt request) {
        return pods.attempt(user, request.operationId(), request.tracking(), request.capturedAt(), request.version());
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody Login request) {
        return auth.login(request.username(), request.password());
    }

    @GetMapping("/me")
    public Map<String, Object> me(@RequestAttribute("mobileUser") Usuario user) { return MobileAuth.profile(user); }

    @PostMapping("/logout")
    public Map<String, Object> logout(@RequestHeader("Authorization") String header) {
        auth.logout(header);
        return Map.of("ok", true);
    }

    @GetMapping("/packages/{tracking}")
    public Map<String, Object> lookup(@PathVariable String tracking) { return pods.lookup(tracking); }

    @PostMapping(value = "/pods", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> deliver(@RequestAttribute("mobileUser") Usuario user,
            @RequestParam String operationId, @RequestParam String tracking,
            @RequestParam Instant capturedAt, @RequestParam String version,
            @RequestPart(required = false) MultipartFile parcel,
            @RequestPart(required = false) MultipartFile recipient,
            @RequestPart(required = false) MultipartFile proof) throws IOException {
        if (proof != null) {
            if (parcel != null || recipient != null) throw new IllegalArgumentException("Envíe una sola foto del POD");
            return pods.deliverSingle(user, operationId, tracking, capturedAt, version, proof);
        }
        return pods.deliver(user, operationId, tracking, capturedAt, version, parcel, recipient);
    }

    @GetMapping("/reports/deliveries.xlsx")
    public ResponseEntity<byte[]> excel(@RequestAttribute("mobileUser") Usuario user,
            @RequestParam LocalDate from, @RequestParam LocalDate to) throws IOException {
        var rows = pods.report(user, from, to);
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("Entregas móvil");
            String[] titles = {"Tracking ID", "Mensajero", "Fecha de entrega", "Hora de entrega", "Origen"};
            var header = sheet.createRow(0);
            for (int i = 0; i < titles.length; i++) header.createCell(i).setCellValue(titles[i]);
            var dateStyle = workbook.createCellStyle();
            dateStyle.setDataFormat(workbook.createDataFormat().getFormat("dd/mm/yyyy"));
            var timeStyle = workbook.createCellStyle();
            timeStyle.setDataFormat(workbook.createDataFormat().getFormat("hh:mm:ss"));
            int index = 1;
            for (var record : rows) {
                var row = sheet.createRow(index++);
                row.createCell(0).setCellValue(String.valueOf(record.get("tracking_code")));
                row.createCell(1).setCellValue(String.valueOf(record.get("mensajero_nombre")));
                var delivered = java.time.LocalDateTime.parse(record.get("delivered_local").toString());
                row.createCell(2).setCellValue(delivered.toLocalDate());
                row.getCell(2).setCellStyle(dateStyle);
                row.createCell(3).setCellValue(delivered.toLocalTime().toSecondOfDay() / 86400.0);
                row.getCell(3).setCellStyle(timeStyle);
                row.createCell(4).setCellValue("Aplicación móvil");
            }
            sheet.createFreezePane(0, 1);
            for (int i = 0; i < titles.length; i++) sheet.autoSizeColumn(i);
            workbook.write(output);
            return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=entregas-movil.xlsx")
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(output.toByteArray());
        }
    }
}
