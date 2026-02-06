package com.cargosfsr.inventario.controllers;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.cargosfsr.inventario.services.ImportService;

@RestController
@RequestMapping("/api/import")
public class ImportController {

    private final ImportService importService;

    public ImportController(ImportService importService) {
        this.importService = importService;
    }

    @PostMapping(path = "/consolidado", consumes = {"multipart/form-data"})
    public ResponseEntity<?> importarConsolidado(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(error("Archivo vacío", null));
        }
        try {
            Map<String, Object> res = importService.importarConsolidadoXLSX(file);
            return ResponseEntity.ok(res);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(error(ex.getMessage(), null));
        } catch (DataAccessException ex) {
            return dbError(ex);
        } catch (Exception ex) {
            return serverError(ex);
        }
    }

    @PostMapping(path = "/paquetes", consumes = {"multipart/form-data"})
    public ResponseEntity<?> importarPaquetes(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(error("Archivo vacío", null));
        }
        try {
            Map<String, Object> res = importService.importarPaquetesCSV(file);
            return ResponseEntity.ok(res);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(error(ex.getMessage(), null));
        } catch (DataAccessException ex) {
            return dbError(ex);
        } catch (Exception ex) {
            return serverError(ex);
        }
    }

    // ---------------- helpers de respuesta ----------------

    private static Map<String, Object> error(String msg, String detail) {
        Map<String, Object> m = new HashMap<>();
        m.put("error", msg);
        if (StringUtils.hasText(detail)) m.put("detail", detail);
        return m;
    }

    private static ResponseEntity<?> dbError(DataAccessException ex) {
        String msg = rootMessage(ex);
        String sqlState = extractSqlState(ex);
        Map<String, Object> body = error("Error de base de datos", msg);
        if (sqlState != null) body.put("sqlstate", sqlState);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    private static ResponseEntity<?> serverError(Exception ex) {
        String msg = rootMessage(ex);
        Map<String, Object> body = error("Error interno", msg);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        String last = t.getMessage();
        while (cur.getCause() != null) {
            cur = cur.getCause();
            if (StringUtils.hasText(cur.getMessage())) last = cur.getMessage();
        }
        return last;
    }

    private static String extractSqlState(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            if (cur instanceof SQLException) {
                return ((SQLException) cur).getSQLState();
            }
            cur = cur.getCause();
        }
        return null;
    }
}
