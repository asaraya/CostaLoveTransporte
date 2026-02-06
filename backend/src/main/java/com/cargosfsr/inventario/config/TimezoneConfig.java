package com.cargosfsr.inventario.config;

import java.util.TimeZone;

import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;

@Configuration
public class TimezoneConfig {

    private static final String TZ = "America/Costa_Rica";

    @PostConstruct
    public void init() {
        // Zona por defecto de la JVM
        TimeZone.setDefault(TimeZone.getTimeZone(TZ));
        // (Opcional) Validación simple:
        // System.out.println("JVM Default TZ = " + TimeZone.getDefault().getID());
        // A partir de spring.jackson.time-zone, Jackson serializa en CR.
        // No necesitamos tocar ObjectMapper aquí porque ya lo fija application-prod.properties
    }
}
