package com.cargosfsr.inventario.services;

import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.text.DecimalFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.cargosfsr.inventario.auth.CurrentUser;


/**
 * Servicio de importación de archivos:
 *
 * 1) Consolidado (XLSX) – crea sacos y paquetes (si no existen),
 *    actualiza distrito y fecha de recepción a partir del archivo,
 *    evita duplicados y registra actor.
 *
 * 2) Tracks (CSV) – completa datos de paquetes existentes (no crea nuevos),
 *    aplica status externo (SP) y actualiza datos generales.
 */
@Service
public class ImportService {

    private final JdbcTemplate jdbc;
    private final CurrentUser currentUser; // actor = usuario logueado

    public ImportService(JdbcTemplate jdbc, CurrentUser currentUser) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
    }

    // ==========================
    // Distritos conocidos + "PENDIENTE"
    // ==========================
    private static final String DISTRITO_PENDIENTE = "PENDIENTE";

    private static final Map<String, String> DISTRITO_CANON = Map.of(
        "la colonia", "La colonia",
        "jimenez", "Jimenez",
        "colorado", "Colorado",
        "la rita", "La Rita",
        "roxana", "Roxana",
        "pendiente", "PENDIENTE"
    );

    private static String canonDistrito(String raw) {
        if (raw == null) return null;
        String t = raw.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);

        // soporta "Distrito: Roxana" / "ROXANA" / etc.
        if (t.contains("la colonia")) return "La colonia";
        if (t.contains("jimenez")) return "Jimenez";
        if (t.contains("colorado")) return "Colorado";
        if (t.contains("la rita")) return "La Rita";
        if (t.contains("roxana")) return "Roxana";
        if (t.contains("pendiente")) return "PENDIENTE";

        return DISTRITO_CANON.get(t); // exact match fallback
    }

    // =====================================================================
    // 1) CONSOLIDADO (XLSX) – autodetecta columnas tipo:
    //    (Fecha, Marchamo, Distrito, Tracking, Responsable, Observaciones).
    // =====================================================================
    @Transactional
    @CacheEvict(cacheNames = { "inventario", "busquedas" }, allEntries = true)
    public Map<String, Object> importarConsolidadoXLSX(MultipartFile file) throws Exception {
        long t0 = System.currentTimeMillis();

        List<ConsoRow> rows = new ArrayList<>(4096);

        try (var is = file.getInputStream(); Workbook wb = WorkbookFactory.create(is)) {
            Sheet sh = wb.getSheet("CONSOLIDADO OFICIAL");
            if (sh == null) sh = wb.getSheetAt(0);

            Map<String, Integer> hIndex = new HashMap<>();
            Row header = sh.getRow(sh.getFirstRowNum());
            if (header != null) {
                for (int c = header.getFirstCellNum(); c < header.getLastCellNum(); c++) {
                    String name = getString(header.getCell(c));
                    if (name != null) {
                        hIndex.put(name.trim().toUpperCase(Locale.ROOT), c);
                    }
                }
            }

            Integer colFecha         = headerIndex(hIndex, "FECHA");
            Integer colTracking      = headerIndex(hIndex, "TRACKING");
            Integer colMarchamo      = headerIndex(hIndex, "MARCHAMO");
            Integer colDistrito      = headerIndex(hIndex, "DISTRITO", "DISTRICT", "ZONA", "UBICACION", "UBICACIÓN", "MUEBLE"); // soporta viejas cabeceras
            Integer colResponsable   = headerIndex(hIndex, "RESPONSABLE", "RESP"); // opcional
            Integer colObservaciones = headerIndex(hIndex, "OBSERVACIONES", "OBSERVACION", "OBS", "NOTAS", "NOTA");

            if (colFecha == null) colFecha = 0;
            if (colTracking == null) colTracking = detectTrackingColumn(sh);

            for (int r = sh.getFirstRowNum() + 1; r <= sh.getLastRowNum(); r++) {
                Row row = sh.getRow(r);
                if (row == null) continue;

                String marchamoActual = null;
                String distritoActual = null;

                if (colMarchamo != null) {
                    String raw = getCellStr(row.getCell(colMarchamo));
                    if (raw != null && !raw.isBlank()) {
                        marchamoActual = raw.trim();
                    }
                }
                if (colDistrito != null) {
                    String raw = getString(row.getCell(colDistrito));
                    if (raw != null && !raw.isBlank()) {
                        distritoActual = canonDistrito(raw);
                    }
                }

                if (marchamoActual == null || distritoActual == null) {
                    Marker mk = scanRowForMarker(row);
                    if (distritoActual == null && mk.distrito != null) distritoActual = mk.distrito;
                    if (marchamoActual  == null && mk.marchamo  != null) marchamoActual  = mk.marchamo;
                }

                // Responsable por fila (si existe y viene valor)
                String responsableFila = null;
                if (colResponsable != null) {
                    String resp = getString(row.getCell(colResponsable));
                    if (notBlank(resp)) responsableFila = resp.trim();
                }

                // OBSERVACIONES: SOLO si la celda es texto; si no, se deja vacío.
                String observacionesFila = null;
                if (colObservaciones != null) {
                    String obs = getObservacionStrict(row.getCell(colObservaciones));
                    if (obs != null) observacionesFila = obs;
                }

                Timestamp llegada = null;
                try {
                    Date f = parseFecha(row.getCell(colFecha));
                    if (f != null) llegada = new Timestamp(f.getTime());
                } catch (Exception ignore) {}

                List<String> trackings = new ArrayList<>();
                if (colTracking != null) {
                    String cellVal = getCellStr(row.getCell(colTracking));
                    trackings.addAll(findAllTrackings(cellVal));
                }
                if (trackings.isEmpty()) {
                    StringBuilder sb = new StringBuilder();
                    for (Cell c : row) {
                        String v = getCellStr(c);
                        if (v != null) sb.append(v).append(' ');
                    }
                    trackings.addAll(findAllTrackings(sb.toString()));
                }

                // ===== EARLY EXIT: si esta fila NO tiene tracking y las 2 próximas tampoco, cortar =====
                if (trackings.isEmpty()) {
                    boolean next1Has = rowHasTracking((r + 1) <= sh.getLastRowNum() ? sh.getRow(r + 1) : null, colTracking);
                    boolean next2Has = rowHasTracking((r + 2) <= sh.getLastRowNum() ? sh.getRow(r + 2) : null, colTracking);
                    if (!next1Has && !next2Has) {
                        break;
                    } else {
                        continue;
                    }
                }
                // ======================================================================================

                for (String tracking : trackings) {
                    if (tracking == null || tracking.isBlank()) continue;
                    ConsoRow cr = new ConsoRow(
                        tracking.trim(),
                        marchamoActual,
                        distritoActual,
                        llegada,
                        responsableFila,
                        observacionesFila
                    );
                    rows.add(cr);
                }
            }
        }

        // uniq por tracking (última fila gana)
        Map<String, ConsoRow> byTracking = new LinkedHashMap<>();
        for (ConsoRow r : rows) {
            if (r.tracking == null) continue;
            byTracking.put(r.tracking, r);
        }
        List<ConsoRow> uniqRows = new ArrayList<>(byTracking.values());

        // placeholders
        long sacoPend = ensureSaco("PENDIENTE");
        long distPend = ensureDistrito(DISTRITO_PENDIENTE);

        // Map distritos existentes (nombre canonical -> id)
        Map<String, Long> distMap = jdbc.query("SELECT id, nombre FROM distritos",
            rs -> {
                Map<String, Long> m = new HashMap<>();
                while (rs.next()) {
                    String canon = canonDistrito(rs.getString("nombre"));
                    if (canon == null) canon = rs.getString("nombre");
                    m.put(canon, rs.getLong("id"));
                }
                return m;
            });

        // crear sacos faltantes
        List<String> marchamos = uniqRows.stream()
                .map(v -> v.marchamo)
                .filter(ImportService::notBlank)
                .distinct()
                .collect(Collectors.toList());
        batchInsertIgnoreSacos(marchamos);

        Map<String, Long> sacoMap = jdbc.query("SELECT id, marchamo FROM sacos",
                rs -> {
                    Map<String, Long> m = new HashMap<>();
                    while (rs.next()) {
                        m.put(rs.getString("marchamo"), rs.getLong("id"));
                    }
                    return m;
                });

        List<String> trackings = uniqRows.stream()
                .map(v -> v.tracking)
                .distinct()
                .collect(Collectors.toList());

        String actor = currentUser.display();

        // Inserta paquetes faltantes con saco/distrito PENDIENTE y estado base "DISPONIBLE"
        try {
            jdbc.update("SET @changed_by = ?", actor);
            batchInsertIgnorePaquetes(trackings, sacoPend, distPend);
        } finally {
            jdbc.update("SET @changed_by = NULL");
        }

        int conMarcadores = batchUpdatePaquetes(uniqRows, sacoMap, distMap, sacoPend, distPend, actor);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", trackings.size());
        out.put("procesados", trackings.size());
        out.put("con_marcadores", conMarcadores);
        out.put("sin_marcadores", trackings.size() - conMarcadores);
        out.put("ms", System.currentTimeMillis() - t0);
        out.put("actor", actor);
        return out;
    }


    /** Devuelve el índice de la primera cabecera que coincida con alguno de los nombres dados */
    private static Integer headerIndex(Map<String,Integer> hIndex, String... names) {
        if (hIndex == null || hIndex.isEmpty() || names == null) return null;
        for (String n : names) {
            if (n == null) continue;
            Integer idx = hIndex.get(n.toUpperCase(Locale.ROOT));
            if (idx != null) return idx;
        }
        return null;
    }

    /** Extrae TODOS los trackings que parezcan válidos de un texto dado */
    private static List<String> findAllTrackings(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        Matcher m = P_TRACK.matcher(text);
        while (m.find()) {
            String t = m.group();
            if (t != null && !t.isBlank()) {
                out.add(t.trim().toUpperCase(Locale.ROOT));
            }
        }
        return out;
    }

    private static String clip(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    // =====================================================================
    // 2) TRACKS (CSV) -> actualizar SOLO existentes (no crea nuevos)
    // =====================================================================
    @Transactional
    @CacheEvict(cacheNames = { "inventario", "busquedas" }, allEntries = true)
    public Map<String, Object> importarPaquetesCSV(MultipartFile file) throws Exception {
        int total = 0, entregados = 0, devoluciones = 0, actualizados = 0, noExistentes = 0, rechazados = 0;
        List<String> errores = new ArrayList<>();

        String csv = decodeBestEffort(file);
        String firstLine = csv.contains("\n") ? csv.substring(0, csv.indexOf('\n')) : csv;
        char delimiter = detectDelimiterFromLine(firstLine);

        CSVFormat fmt = CSVFormat.DEFAULT
                .withDelimiter(delimiter)
                .withFirstRecordAsHeader()
                .withIgnoreHeaderCase()
                .withTrim();

        List<CsvRow> filas = new ArrayList<>();

        try (CSVParser parser = new CSVParser(new StringReader(csv), fmt)) {
            Map<String, Integer> header = new HashMap<>();
            parser.getHeaderMap().forEach((k, v) -> header.put(k.toUpperCase(Locale.ROOT), v));

            for (CSVRecord row : parser) {
                total++;

                String cand = get(row, header,
                        "AEROTRACK", "COURIER_NUMBER", "AWB", "TRK_BAGNUM",
                        "TRACKING", "TRACKING_NUMBER",
                        "NUMERO DE ENVIO", "NUMERO_DE_ENVIO",
                        "CÓDIGO ENVÍO", "CODIGO ENVIO");
                String tracking = isValidTracking(cand) ? cand : null;
                if (tracking == null) {
                    for (int i = 0; i < row.size(); i++) {
                        String v = row.get(i);
                        String t = findTrackingInText(v);
                        if (isValidTracking(t)) { tracking = t; break; }
                    }
                }
                if (isBlank(tracking)) {
                    rechazados++; errores.add("Fila " + row.getRecordNumber() + ": tracking inválido o vacío");
                    continue;
                }
                tracking = tracking.trim().toUpperCase(Locale.ROOT);

                String nombre    = get(row, header, "CLIENT_NAME", "CONSIGNEE", "NOMBRE", "NAME");
                String direccion = get(row, header, "THIRDPARTY_ADDRESS", "DIRECCION", "DIRECCIÓN", "ADDRESS");

                String telefono = get(row, header,
                        "THIRDPARTY_PHONE", "THIRDPARTY PHONE", "THIRD_PARTY_PHONE", "THIRD PARTY PHONE");

                if (telefono != null) {
                    String t = telefono.replaceAll("[^\\d+]", "");
                    if (!t.startsWith("+") && (t.length() == 8 || (t.startsWith("506") && t.length()==11))) {
                        t = (t.length()==8) ? "+506"+t : "+"+t;
                    }
                    telefono = t;
                }

                BigDecimal valor = parseDecimal(firstNonNull(
                        get(row,header,"MERCHANDISE_VALUE","VALOR","VALOR_MERCANCIA","VALUE"),
                        get(row,header,"DECLARED_VALUE")
                ));
                String contenido = get(row, header, "DESCRIPTION", "DESCRIPCION", "CONTENT_DESCRIPTION", "CONTENIDO");
                String statusRaw = get(row, header, "STATUS", "ESTADO");
                String fupdate   = get(row, header, "LAST_UPDATE", "FECHA", "LAST UPDATE", "FECHA ULTIMA ACTUALIZACION", "FECHA_ULTIMA_ACTUALIZACION");

                // Distrito (opcional, si el CSV lo trae)
                String distritoRaw = get(row, header, "DISTRITO", "DISTRICT", "ZONA", "UBICACION", "UBICACIÓN", "MUEBLE");
                String distritoCanon = canonDistrito(distritoRaw);

                Timestamp statusAt = parseDateToTs(fupdate);
                filas.add(new CsvRow(row.getRecordNumber(), tracking, nombre, direccion, telefono, valor, contenido, statusRaw, statusAt, distritoCanon));
            }
        }

        if (filas.isEmpty()) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("total", total);
            out.put("entregados", 0);
            out.put("devoluciones", 0);
            out.put("actualizados", 0);
            out.put("no_existentes", 0);
            out.put("rechazados", rechazados);
            out.put("errores", errores);
            return out;
        }

        Set<String> solicitados = filas.stream().map(f -> f.tracking).collect(Collectors.toSet());
        Set<String> existentes = fetchExistentes(solicitados);

        String actor = currentUser.display();

        jdbc.update("SET @changed_by = ?", actor);
        try {
            for (CsvRow f : filas) {
                if (!existentes.contains(f.tracking)) {
                    noExistentes++; errores.add("Fila " + f.recNo + " (" + f.tracking + "): no existe en BD, saltado.");
                    continue;
                }
                try {
                    // Si el CSV no trae distrito válido, preserva el distrito actual del paquete.
                    String distritoParaSP = f.distritoCanon;
                    if (isBlank(distritoParaSP)) {
                        distritoParaSP = jdbc.queryForObject(
                            "SELECT distrito_nombre FROM vw_paquete_resumen WHERE tracking_code=? LIMIT 1",
                            String.class,
                            f.tracking
                        );
                    }
                    if (isBlank(distritoParaSP)) {
                        // fallback extremo (no debería ocurrir porque distrito_id es NOT NULL)
                        distritoParaSP = "PENDIENTE";
                        ensureDistrito("PENDIENTE");
                    }

                    jdbc.update("CALL sp_upsert_paquete_base(?,?,?,?,?,?,?)",
                            f.tracking,
                            emptyToNull(f.nombre),
                            emptyToNull(f.direccion),
                            emptyToNull(f.telefono),
                            f.valor,
                            emptyToNull(f.contenido),
                            distritoParaSP
                    );

                    String status = opt(f.statusRaw);
                    if (!isBlank(status)) {
                        jdbc.update("CALL sp_aplicar_status_externo(?,?,?,?)",
                                f.tracking, status, f.statusAt, actor);

                        jdbc.update("UPDATE paquetes SET cambio_en_sistema_por=? WHERE tracking_code=?", actor, f.tracking);

                        String norm = normalize(status);
                        boolean isEntregado = norm.contains("prueba de entrega") || norm.startsWith("entregado")
                                || norm.startsWith("en entrega") || norm.startsWith("delivered")
                                || norm.contains("proof of delivery");
                        boolean isDevolucion = norm.contains("transito a bodegas") || norm.startsWith("devolucion")
                                || norm.startsWith("devoluciones") || norm.startsWith("devuelto")
                                || norm.startsWith("almacenaje") || norm.contains("in transit to warehouse")
                                || norm.contains("in transit to warehouses") || norm.startsWith("return")
                                || norm.startsWith("returned") || norm.startsWith("storage");

                        if (isDevolucion) devoluciones++;
                        else if (isEntregado) entregados++;
                        else actualizados++;
                    } else {
                        actualizados++;
                        jdbc.update("UPDATE paquetes SET cambio_en_sistema_por=? WHERE tracking_code=?", actor, f.tracking);
                    }
                } catch (Exception ex) {
                    rechazados++; errores.add("Fila " + f.recNo + " (" + f.tracking + "): " + ex.getMessage());
                }
            }
        } finally {
            jdbc.update("SET @changed_by = NULL");
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total);
        out.put("entregados", entregados);
        out.put("devoluciones", devoluciones);
        out.put("actualizados", actualizados);
        out.put("no_existentes", noExistentes);
        out.put("rechazados", rechazados);
        out.put("errores", errores);
        out.put("actor", actor);
        return out;
    }


    /* ===================== Tipos y utilidades internas ===================== */

    /** Contenedor para filas del consolidado */
    private static class ConsoRow {
        final String tracking;
        final String marchamo;
        final String distrito;       // <-- antes "ubicacion"
        final Timestamp receivedAt;
        final String responsable;    // opcional (del XLSX)
        final String observaciones;

        ConsoRow(String t, String m, String distrito, Timestamp ra, String responsable, String observaciones) {
            this.tracking = t != null ? t.toUpperCase(Locale.ROOT) : null;
            this.marchamo = m;
            this.distrito = distrito; // canonical o null
            this.receivedAt = ra;
            this.responsable = responsable;
            this.observaciones = observaciones;
        }
    }

    /** Marcador parcial en una fila */
    private static class Marker {
        String marchamo;
        String distrito;
    }

    /** Contenedor para filas del CSV */
    private static class CsvRow {
        final long recNo;
        final String tracking, nombre, direccion, telefono, contenido, statusRaw;
        final BigDecimal valor;
        final Timestamp statusAt;
        final String distritoCanon; // opcional

        CsvRow(long recNo, String tracking, String nombre, String direccion, String telefono,
               BigDecimal valor, String contenido, String statusRaw, Timestamp statusAt, String distritoCanon) {
            this.recNo = recNo;
            this.tracking = tracking;
            this.nombre   = nombre;
            this.direccion = direccion;
            this.telefono  = telefono;
            this.valor      = valor;
            this.contenido  = contenido;
            this.statusRaw  = statusRaw;
            this.statusAt   = statusAt;
            this.distritoCanon = distritoCanon;
        }
    }

    /** Inserta sacos faltantes, ignorando duplicados */
    private void batchInsertIgnoreSacos(List<String> marchamos) {
        if (marchamos.isEmpty()) return;
        List<String> uniq = marchamos.stream().filter(ImportService::notBlank).distinct().toList();
        jdbc.batchUpdate("INSERT IGNORE INTO sacos(marchamo) VALUES (?)",
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(java.sql.PreparedStatement ps, int i) throws java.sql.SQLException {
                        ps.setString(1, uniq.get(i));
                    }
                    @Override
                    public int getBatchSize() { return uniq.size(); }
                });
    }

    /** Inserta paquetes faltantes, asignándoles saco/distrito PENDIENTE y estado base (DISPONIBLE) */
    private void batchInsertIgnorePaquetes(List<String> trackings, long sacoPend, long distPend) {
        if (trackings.isEmpty()) return;
        List<String> uniq = trackings.stream().filter(ImportService::notBlank).distinct().toList();
        jdbc.batchUpdate(
            "INSERT IGNORE INTO paquetes(tracking_code, saco_id, distrito_id, estado) VALUES (?,?,?, 'NO_ENTREGADO_CONSIGNATARIO_DISPONIBLE')",
            new BatchPreparedStatementSetter() {
                @Override
                public void setValues(java.sql.PreparedStatement ps, int i) throws java.sql.SQLException {
                    ps.setString(1, uniq.get(i).toUpperCase(Locale.ROOT));
                    ps.setLong(2, sacoPend);
                    ps.setLong(3, distPend);
                }
                @Override
                public int getBatchSize() { return uniq.size(); }
            }
        );
    }

    /**
     * Actualiza paquete (saco, distrito y received_at).
     * También deja rastro del actor y guarda el responsable del consolidado si viene.
     * Además, si viene "observaciones" desde el XLSX, la setea; si no viene, no toca el valor previo.
     *
     * @return cantidad de paquetes con marchamo+distrito reales (no PENDIENTE)
     */
    private int batchUpdatePaquetes(List<ConsoRow> rows,
                                    Map<String, Long> sacoMap,
                                    Map<String, Long> distMap,
                                    long sacoPend, long distPend,
                                    String actor) {
        if (rows.isEmpty()) return 0;

        final int BATCH = 500;
        int conMarcadores = 0;

        for (int from = 0; from < rows.size(); from += BATCH) {
            int to = Math.min(from + BATCH, rows.size());
            List<ConsoRow> slice = rows.subList(from, to);

            for (ConsoRow r : slice) {
                Long sId = (r.marchamo == null) ? null : sacoMap.get(r.marchamo);
                Long dId = (r.distrito == null) ? null : distMap.get(r.distrito);
                if (sId != null && dId != null && sId != sacoPend && dId != distPend) conMarcadores++;
            }

            jdbc.batchUpdate(
                "UPDATE paquetes " +
                "   SET saco_id=?, distrito_id=?, received_at=?, " +
                "       cambio_en_sistema_por=?, " +
                "       observaciones = COALESCE(?, observaciones), " +
                "       responsable_consolidado = COALESCE(?, responsable_consolidado) " +
                " WHERE tracking_code=?",
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(java.sql.PreparedStatement ps, int i) throws java.sql.SQLException {
                        ConsoRow r = slice.get(i);

                        Long sacoId = (r.marchamo == null) ? null : sacoMap.get(r.marchamo);
                        Long distId = (r.distrito == null) ? null : distMap.get(r.distrito);

                        ps.setLong(1, sacoId == null ? sacoPend : sacoId);
                        ps.setLong(2, distId == null ? distPend : distId);

                        if (r.receivedAt == null) ps.setNull(3, java.sql.Types.TIMESTAMP);
                        else ps.setTimestamp(3, r.receivedAt);

                        ps.setString(4, actor);

                        if (r.observaciones == null || r.observaciones.isEmpty()) ps.setNull(5, java.sql.Types.VARCHAR);
                        else ps.setString(5, clipNoTrim(r.observaciones, 500));

                        if (r.responsable == null || r.responsable.isBlank()) ps.setNull(6, java.sql.Types.VARCHAR);
                        else ps.setString(6, clipNoTrim(r.responsable, 100));

                        ps.setString(7, r.tracking);
                    }

                    @Override
                    public int getBatchSize() { return slice.size(); }
                }
            );
        }
        return conMarcadores;
    }

    // ===================== Helpers de codificación y detección =================

    // Leer observaciones SOLO si la celda es texto (o fórmula que produce texto).
    private static String getObservacionStrict(Cell c) {
        if (c == null) return null;
        CellType t = c.getCellType();
        if (t == CellType.STRING) {
            String s = c.getStringCellValue();
            return (s == null || s.isEmpty()) ? null : s;
        }
        if (t == CellType.FORMULA) {
            if (c.getCachedFormulaResultType() == CellType.STRING) {
                String s = c.getRichStringCellValue() != null ? c.getRichStringCellValue().getString() : null;
                return (s == null || s.isEmpty()) ? null : s;
            }
        }
        return null;
    }

    private static String clipNoTrim(String s, int max) {
        if (s == null) return null;
        return (s.length() > max) ? s.substring(0, max) : s;
    }

    /** Descifra mejor esfuerzo: UTF-8, Windows-1252 e ISO-8859-1 */
    private static String decodeBestEffort(MultipartFile file) throws Exception {
        byte[] bytes = file.getBytes();

        String utf8   = new String(bytes, StandardCharsets.UTF_8);
        String cp1252 = new String(bytes, Charset.forName("windows-1252"));
        String iso    = new String(bytes, Charset.forName("ISO-8859-1"));

        int scoreUtf8  = badCharScore(utf8);
        int score1252  = badCharScore(cp1252);
        int scoreIso   = badCharScore(iso);

        String best = utf8;
        int bestScore = scoreUtf8;
        if (score1252 < bestScore) { best = cp1252; bestScore = score1252; }
        if (scoreIso   < bestScore) { best = iso;   bestScore = scoreIso;   }

        return best;
    }

    private static int badCharScore(String s) {
        if (s == null) return Integer.MAX_VALUE;
        int score = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\uFFFD') score += 5;
        }
        score += countSubstr(s, "Ã");
        score += countSubstr(s, "Â");
        return score;
    }

    private static int countSubstr(String s, String sub) {
        int count = 0, idx = 0;
        while ((idx = s.indexOf(sub, idx)) != -1) {
            count++;
            idx += sub.length();
        }
        return count;
    }

    private static char detectDelimiterFromLine(String headerLine) {
        int commas = countSubstr(headerLine, ",");
        int semis  = countSubstr(headerLine, ";");
        int tabs   = countSubstr(headerLine, "\t");
        if (semis >= commas && semis >= tabs) return ';';
        if (commas >= semis && commas >= tabs) return ',';
        return '\t';
    }

    // ===================== Helpers de Tracking ==========================

    /** Encuentra la columna probable de tracking por heurística (primeras ~200 filas) */
    private static Integer detectTrackingColumn(Sheet sh) {
        int start = Math.max(sh.getFirstRowNum(), 0);
        int end   = Math.min(sh.getLastRowNum(), start + 200);
        Integer guess = null;
        int bestScore = -1;

        for (int c = 0; c < 30; c++) {
            int score = 0;
            for (int r = start + 1; r <= end; r++) {
                Row row = sh.getRow(r);
                if (row == null) continue;
                String v = getCellStr(row.getCell(c));
                if (v != null && looksLikeTracking(v)) score++;
            }
            if (score > bestScore) {
                bestScore = score;
                guess = (score == 0 ? null : c);
            }
        }
        return guess;
    }

    // Regex amplio
    private static final Pattern P_TRACK = Pattern.compile("(?i)\\b(?!MUEBLE)(?!CAJA)(?!DISTRITO)[A-Z]{2,4}[A-Z0-9]{6,18}\\b");

    private static boolean looksLikeTracking(String s) {
        if (s == null) return false;
        String t = s.trim();
        if (t.length() < 8 || t.length() > 24) return false;
        if (t.matches("\\d+")) return false;
        String up = t.toUpperCase(Locale.ROOT);
        if (up.startsWith("MUEBLE") || up.startsWith("CAJA") || up.startsWith("DISTRITO")) return false;
        return P_TRACK.matcher(t).find();
    }

    private static boolean isValidTracking(String s) {
        return looksLikeTracking(s);
    }

    private static String findTrackingInText(String v) {
        if (v == null) return null;
        Matcher m = P_TRACK.matcher(v);
        return m.find() ? m.group().trim().toUpperCase(Locale.ROOT) : null;
    }

    /** Escanea una fila para ver si contiene marchamo y/o distrito */
    private static Marker scanRowForMarker(Row row) {
        Marker mk = new Marker();
        for (Cell c : row) {
            String s = getString(c);
            if (s == null) continue;
            String raw = s.trim();
            if (raw.isEmpty()) continue;

            // marchamo: números (mínimo 4)
            if (mk.marchamo == null && raw.toUpperCase(Locale.ROOT).matches("\\d{4,}")) {
                mk.marchamo = raw;
                continue;
            }

            // distrito: buscar en cualquier texto de celda
            if (mk.distrito == null) {
                String canon = canonDistrito(raw);
                if (canon != null) mk.distrito = canon;
            }
        }
        return mk;
    }

    // ===================== Helpers comunes ===============================

    private static boolean notBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String emptyToNull(String s) {
        return isBlank(s) ? null : s;
    }

    private static String opt(String s) {
        return s == null ? "" : s.trim();
    }

    private static String firstNonNull(String a, String b) {
        return isBlank(a) ? b : a;
    }

    private static BigDecimal parseDecimal(String s) {
        if (isBlank(s)) return null;
        try {
            String n = s.replaceAll("[^0-9,\\.\\-]", "");
            if (n.chars().filter(ch -> ch == ',').count() == 1 && !n.contains(".")) {
                n = n.replace(',', '.');
            } else {
                n = n.replace(",", "");
            }
            return new BigDecimal(n);
        } catch (Exception e) {
            return null;
        }
    }

    private static Timestamp parseDateToTs(String s) {
        if (isBlank(s)) return null;
        String[] patterns = {
            "dd/MM/yyyy HH:mm:ss",
            "dd/MM/yyyy HH:mm",
            "dd/MM/yyyy",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd"
        };
        for (String p : patterns) {
            try {
                SimpleDateFormat df = new SimpleDateFormat(p);
                df.setLenient(false);
                return new Timestamp(df.parse(s).getTime());
            } catch (ParseException ignore) {}
        }
        return null;
    }

    private static String normalize(String s) {
        if (s == null) return "";
        String n = s.toLowerCase(Locale.ROOT).trim();
        n = n.replace('í','i').replace('ó','o').replace('á','a')
             .replace('é','e').replace('ú','u').replace('ñ','n');
        return n;
    }

    private static String get(CSVRecord row, Map<String, Integer> header, String... keys) {
        if (row == null || header == null || keys == null) return null;
        for (String k : keys) {
            if (k == null) continue;
            Integer idx = header.get(k.toUpperCase(Locale.ROOT));
            if (idx != null && idx >= 0 && idx < row.size()) {
                String v = row.get(idx);
                if (v != null) {
                    String t = v.trim();
                    if (!t.isEmpty()) return t;
                }
            }
        }
        return null;
    }

    private static String getCellStr(Cell c) {
        if (c == null) return null;
        switch (c.getCellType()) {
            case STRING:  return c.getStringCellValue();
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(c)) {
                    return new SimpleDateFormat("yyyy-MM-dd").format(c.getDateCellValue());
                } else {
                    return new DecimalFormat("#").format(c.getNumericCellValue());
                }
            default:
                return null;
        }
    }

    private static String getString(Cell c) {
        if (c == null) return null;
        switch (c.getCellType()) {
            case STRING:  return c.getStringCellValue();
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(c)) {
                    return c.getDateCellValue().toString();
                } else {
                    return new DecimalFormat("#").format(c.getNumericCellValue());
                }
            case FORMULA: return c.getCellFormula();
            case BOOLEAN: return Boolean.toString(c.getBooleanCellValue());
            default:      return null;
        }
    }

    private static Date parseFecha(Cell c) {
        if (c == null) return null;
        if (c.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(c)) {
            return c.getDateCellValue();
        }
        String s = getString(c);
        if (s == null) return null;
        try {
            SimpleDateFormat df1 = new SimpleDateFormat("dd/MM/yyyy");
            SimpleDateFormat df2 = new SimpleDateFormat("yyyy-MM-dd");
            df1.setLenient(false);
            df2.setLenient(false);
            return s.contains("/") ? df1.parse(s) : df2.parse(s);
        } catch (Exception ignore) {
            return null;
        }
    }

    private Set<String> fetchExistentes(Set<String> trackings) {
        Set<String> out = new HashSet<>();
        if (trackings == null || trackings.isEmpty()) return out;
        List<String> list = new ArrayList<>(trackings);
        final int B = 800;
        for (int i = 0; i < list.size(); i += B) {
            List<String> slice = list.subList(i, Math.min(i + B, list.size()));
            String placeholders = slice.stream().map(x -> "?").collect(Collectors.joining(","));
            String sql = "SELECT tracking_code FROM paquetes WHERE tracking_code IN (" + placeholders + ")";
            Object[] params = slice.toArray(new Object[0]);
            List<String> found = jdbc.query(sql, params, (rs, rn) -> rs.getString(1));
            out.addAll(found);
        }
        return out;
    }

    private static boolean rowHasTracking(Row row, Integer colTracking) {
        if (row == null) return false;
        if (colTracking != null) {
            String val = getCellStr(row.getCell(colTracking));
            if (!findAllTrackings(val).isEmpty()) return true;
        }
        StringBuilder sb = new StringBuilder();
        for (Cell c : row) {
            String v = getCellStr(c);
            if (v != null) sb.append(v).append(' ');
        }
        return !findAllTrackings(sb.toString()).isEmpty();
    }

    // ===== Infra: asegurar placeholders =====
    private long ensureSaco(String marchamo) {
        jdbc.update("INSERT IGNORE INTO sacos(marchamo) VALUES (?)", marchamo);
        Long id = jdbc.queryForObject("SELECT id FROM sacos WHERE marchamo=? LIMIT 1", Long.class, marchamo);
        if (id == null) throw new IllegalStateException("No se pudo asegurar saco: " + marchamo);
        return id;
    }

    private long ensureDistrito(String nombre) {
        jdbc.update("INSERT IGNORE INTO distritos(nombre, activo) VALUES (?, 1)", nombre);
        Long id = jdbc.queryForObject("SELECT id FROM distritos WHERE nombre=? LIMIT 1", Long.class, nombre);
        if (id == null) throw new IllegalStateException("No se pudo asegurar distrito: " + nombre);
        return id;
    }

}
