package com.cargosfsr.inventario.auth;

public record UsuarioDto(
    Long id,
    String username,
    String fullName,
    String role
) {}
