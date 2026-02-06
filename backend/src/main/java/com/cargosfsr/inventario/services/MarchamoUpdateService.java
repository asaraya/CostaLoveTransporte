package com.cargosfsr.inventario.services;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.cargosfsr.inventario.model.Distrito;
import com.cargosfsr.inventario.model.Paquete;
import com.cargosfsr.inventario.model.Saco;
import com.cargosfsr.inventario.repository.DistritoRepository;
import com.cargosfsr.inventario.repository.PaqueteRepository;
import com.cargosfsr.inventario.repository.SacoRepository;

@Service
public class MarchamoUpdateService {

    private final PaqueteRepository paquetes;
    private final SacoRepository sacos;
    private final DistritoRepository distritos;

    public MarchamoUpdateService(PaqueteRepository paquetes, SacoRepository sacos, DistritoRepository distritos) {
        this.paquetes = paquetes;
        this.sacos = sacos;
        this.distritos = distritos;
    }

    private static final Pattern TRACKING_P = Pattern.compile("^[A-Z0-9]{2,}$");
    private static final Pattern MARCHAMO_P = Pattern.compile("^\\d{5,}$"); // ej 368749

    // Solo acepta los distritos definidos (case-insensitive)
    private static final Pattern DISTRITO_P = Pattern.compile(
            "^(LA\\s*COLONIA|JIMENEZ|COLORADO|LA\\s*RITA|ROXANA)$",
            Pattern.CASE_INSENSITIVE
    );

    @Transactional
    public Map<String,Object> actualizarMarchamos(MultipartFile file, boolean createMissingSacos, boolean updateDistrito) throws IOException {
        // 1) Parsear archivo (xlsx o csv): tracking -> (marchamo, distrito opcional)
        Map<String,Asignacion> map = esXlsx(file) ? parseXlsx(file) : parseCsvGrupos(file);

        // 2) Aplicar en BD
        int asignados = 0, creadosSacos = 0, paquetesNoEncontrados = 0, errores = 0, distritoNoExiste = 0;
        List<Map<String,Object>> detalle = new ArrayList<>();

        for (var e : map.entrySet()) {
            String tracking = e.getKey();
            Asignacion a = e.getValue();

            try {
                Paquete p = paquetes.findByTrackingCode(tracking).orElse(null);
                if (p == null) {
                    paquetesNoEncontrados++;
                    detalle.add(Map.of("tracking", tracking, "error", "Paquete no existe"));
                    continue;
                }

                // Distrito (opcional)
                if (updateDistrito && a.distritoNombre != null) {
                    String canon = canonicalDistrito(a.distritoNombre);
                    if (canon == null) {
                        distritoNoExiste++;
                        detalle.add(Map.of("tracking", tracking, "warning", "Distrito inválido: " + a.distritoNombre));
                    } else {
                        Distrito d = distritos.findByNombre(canon).orElse(null);
                        if (d == null) {
                            distritoNoExiste++;
                            detalle.add(Map.of("tracking", tracking, "warning", "Distrito no existe en BD: " + canon));
                        } else {
                            p.setDistrito(d);
                        }
                    }
                }

                // Saco / marchamo (obligatorio)
                if (a.marchamo == null || a.marchamo.isBlank()) {
                    errores++;
                    detalle.add(Map.of("tracking", tracking, "error", "Sin marchamo en asignacion"));
                    continue;
                }

                Saco s = sacos.findByMarchamo(a.marchamo).orElse(null);
                if (s == null) {
                    if (!createMissingSacos) {
                        errores++;
                        detalle.add(Map.of("tracking", tracking, "error", "Saco no existe: " + a.marchamo));
                        continue;
                    }
                    s = new Saco();
                    s.setMarchamo(a.marchamo);
                    s = sacos.save(s);
                    creadosSacos++;
                }

                p.setSaco(s);
                paquetes.save(p);
                asignados++;

            } catch (Exception ex) {
                errores++;
                detalle.add(Map.of("tracking", tracking, "error", ex.getMessage()));
            }
        }

        return Map.of(
            "asignados", asignados,
            "sacos_creados", creadosSacos,
            "paquetes_no_encontrados", paquetesNoEncontrados,
            "distritos_invalidos_o_inexistentes", distritoNoExiste,
            "errores", errores,
            "detalle", detalle
        );
    }

    // ====== Parseos ======

    /** XLSX: escanea todas las celdas; cuando ve un marchamo o distrito lo guarda como contexto;
     * cualquier celda que parezca tracking se asigna al marchamo/distrito vigentes hasta que cambien. */
    private Map<String,Asignacion> parseXlsx(MultipartFile file) throws IOException {
        Map<String,Asignacion> out = new LinkedHashMap<>();
        try (InputStream in = file.getInputStream();
             Workbook wb = new XSSFWorkbook(in)) {

            for (int si = 0; si < wb.getNumberOfSheets(); si++) {
                Sheet sh = wb.getSheetAt(si);
                String currentMarchamo = null;
                String currentDistrito = null;

                for (Row row : sh) {
                    for (Cell cell : row) {
                        String txt = cellToString(cell).trim();
                        if (txt.isEmpty()) continue;

                        if (MARCHAMO_P.matcher(txt).matches()) {
                            currentMarchamo = txt;
                            continue;
                        }
                        if (DISTRITO_P.matcher(txt).matches()) {
                            currentDistrito = txt;
                            continue;
                        }
                        if (TRACKING_P.matcher(txt).matches()) {
                            out.put(txt.toUpperCase(Locale.ROOT), new Asignacion(currentMarchamo, currentDistrito));
                        }
                    }
                }
            }
        }
        return out;
    }

    /** CSV en “formato por grupos”: cualquier columna puede traer marchamo o distrito. */
    private Map<String,Asignacion> parseCsvGrupos(MultipartFile file) throws IOException {
        Map<String,Asignacion> out = new LinkedHashMap<>();
        Charset cs = sniffLatin1(file) ? Charset.forName("ISO-8859-1") : StandardCharsets.UTF_8;

        try (var br = new BufferedReader(new InputStreamReader(file.getInputStream(), cs));
             var parser = CSVParser.parse(br, CSVFormat.DEFAULT)) {

            String currentMarchamo = null;
            String currentDistrito = null;

            for (CSVRecord r : parser) {
                for (String raw : r) {
                    String txt = (raw == null) ? "" : raw.trim();
                    if (txt.isEmpty()) continue;

                    if (MARCHAMO_P.matcher(txt).matches()) {
                        currentMarchamo = txt;
                        continue;
                    }
                    if (DISTRITO_P.matcher(txt).matches()) {
                        currentDistrito = txt;
                        continue;
                    }
                    if (TRACKING_P.matcher(txt).matches()) {
                        out.put(txt.toUpperCase(Locale.ROOT), new Asignacion(currentMarchamo, currentDistrito));
                    }
                }
            }
        }
        return out;
    }

    // ====== helpers ======
    private static boolean esXlsx(MultipartFile f) {
        String n = Optional.ofNullable(f.getOriginalFilename()).orElse("").toLowerCase(Locale.ROOT);
        return n.endsWith(".xlsx") || n.endsWith(".xlsm");
    }

    private static boolean sniffLatin1(MultipartFile file) {
        try {
            byte[] buf = file.getBytes();
            String tryUtf = new String(buf, StandardCharsets.UTF_8);
            return tryUtf.contains("\uFFFD");
        } catch (IOException e) {
            return false;
        }
    }

    private static String cellToString(Cell c) {
        if (c == null) return "";
        return switch (c.getCellType()) {
            case STRING -> c.getStringCellValue();
            case NUMERIC -> (DateUtil.isCellDateFormatted(c) ? c.getDateCellValue().toString()
                    : String.valueOf((long) c.getNumericCellValue()));
            case BOOLEAN -> String.valueOf(c.getBooleanCellValue());
            case FORMULA -> c.getCellFormula();
            default -> "";
        };
    }

    /** Normaliza a los nombres EXACTOS que existen en la BD. */
    private static String canonicalDistrito(String raw) {
        if (raw == null) return null;
        String t = raw.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);

        return switch (t) {
            case "la colonia" -> "La colonia";
            case "jimenez" -> "Jimenez";
            case "colorado" -> "Colorado";
            case "la rita" -> "La Rita";
            case "roxana" -> "Roxana";
            default -> null;
        };
    }

    private record Asignacion(String marchamo, String distritoNombre) {}
}
