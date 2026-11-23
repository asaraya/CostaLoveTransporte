package com.cargosfsr.inventario.services;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

public final class InventarioAlCierre {
  private InventarioAlCierre() {}

  public static boolean esHistorico(LocalDate dia) {
    return dia.isBefore(LocalDate.now(ZoneId.of("America/Costa_Rica")));
  }

  public static List<Map<String, Object>> porEstado(JdbcTemplate jdbc, LocalDate dia) {
    return jdbc.queryForList("CALL sp_th_cierre_estados(?, ?)", esHistorico(dia), dia.plusDays(1).atStartOfDay());
  }
}
