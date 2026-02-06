package com.cargosfsr.inventario;

import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import jakarta.annotation.PostConstruct;

@SpringBootApplication
public class InventarioApplication {

  @PostConstruct
  public void initTz() {
    TimeZone.setDefault(TimeZone.getTimeZone("America/Costa_Rica"));
  }

  public static void main(String[] args) {
    SpringApplication.run(InventarioApplication.class, args);
  }
}
