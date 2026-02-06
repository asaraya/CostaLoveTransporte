package com.cargosfsr.inventario.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
    @NotBlank @Size(min = 3, max = 60) String username,
    @NotBlank @Size(min = 1, max = 150) String fullName,
    @NotBlank @Size(min = 6, max = 255) String password
) {}
