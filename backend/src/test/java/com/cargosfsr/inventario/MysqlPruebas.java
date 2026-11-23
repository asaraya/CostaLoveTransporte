package com.cargosfsr.inventario;

import static org.junit.jupiter.api.Assertions.assertTrue;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

public final class MysqlPruebas {
    private MysqlPruebas() {}

    public static SingleConnectionDataSource conectar() {
        String url = System.getProperty("test.mysql.url");
        assertTrue(url.matches("jdbc:mysql://127[.]0[.]0[.]1:34117/fechas_test_heredia([?].*)?"));
        return new SingleConnectionDataSource(url, "root", "", true);
    }

    public static void migrar(JdbcTemplate jdbc) throws Exception {
        ejecutarScript(jdbc, Path.of("../database/actualizar_fechas.sql"));
    }

    public static void ejecutarScript(JdbcTemplate jdbc, Path migration) throws Exception {
        String delimiter = ";";
        StringBuilder sql = new StringBuilder();
        for (String line : Files.readAllLines(migration)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("--") || trimmed.isEmpty()) continue;
            if (trimmed.startsWith("DELIMITER ")) { delimiter = trimmed.substring(10); continue; }
            sql.append(line).append("\n");
            if (trimmed.endsWith(delimiter)) {
                String statement = sql.toString().trim();
                jdbc.execute(statement.substring(0, statement.length() - delimiter.length()));
                sql.setLength(0);
            }
        }
        assertTrue(sql.toString().isBlank());
    }
}
