package com.cargosfsr.inventario.services;

import java.util.Collection;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class ParametrosJson {
    private static final ObjectMapper JSON = new ObjectMapper();
    private ParametrosJson() {}

    public static String lista(Collection<?> valores) {
        try {
            return JSON.writeValueAsString(valores);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("No se pudo serializar la lista de parámetros", ex);
        }
    }
}
