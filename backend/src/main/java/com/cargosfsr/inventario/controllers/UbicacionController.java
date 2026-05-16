package com.cargosfsr.inventario.controllers;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.cargosfsr.inventario.model.Ubicacion;
import com.cargosfsr.inventario.model.enums.UbicacionTipo;
import com.cargosfsr.inventario.repository.UbicacionRepository;

@RestController
@RequestMapping("/api/ubicaciones")
public class UbicacionController {

    private final UbicacionRepository ubicaciones;

    public UbicacionController(UbicacionRepository ubicaciones) {
        this.ubicaciones = ubicaciones;
    }

    /**
     * Lista de códigos de ubicaciones activas, con filtros opcionales:
     * - tipo: MUEBLE | CAJA  (string tolerante → enum)
     * - q   : filtro "containing ignore case" sobre el código
     */
    @GetMapping("/codigos")
    public List<String> codigos(@RequestParam(name = "tipo", required = false) String tipo,
                                @RequestParam(name = "q", required = false) String q) {
        UbicacionTipo parsedTipo = null;
        if (StringUtils.hasText(tipo)) {
            try {
                parsedTipo = UbicacionTipo.valueOf(tipo.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                parsedTipo = null; // ignora filtro inválido
            }
        }
        final boolean hasQ = StringUtils.hasText(q);

        if (parsedTipo != null && hasQ) {
            return ubicaciones
                .findByTipoAndCodigoContainingIgnoreCaseAndActivoTrueOrderByCodigo(parsedTipo, q.trim())
                .stream().map(Ubicacion::getCodigo).collect(Collectors.toList());
        } else if (parsedTipo != null) {
            // ORDEN NUMÉRICO cuando hay tipo y no hay 'q'
            return ubicaciones
                .findByTipoAndActivoTrueOrderByMuebleNumAscEstanteriaNumAsc(parsedTipo)
                .stream().map(Ubicacion::getCodigo).collect(Collectors.toList());
        } else if (hasQ) {
            return ubicaciones
                .findByCodigoContainingIgnoreCaseAndActivoTrueOrderByCodigo(q.trim())
                .stream().map(Ubicacion::getCodigo).collect(Collectors.toList());
        } else {
            // Sin filtros: orden numérico
            return ubicaciones
                .findByActivoTrueOrderByMuebleNumAscEstanteriaNumAsc()
                .stream().map(Ubicacion::getCodigo).collect(Collectors.toList());
        }
    }
}
