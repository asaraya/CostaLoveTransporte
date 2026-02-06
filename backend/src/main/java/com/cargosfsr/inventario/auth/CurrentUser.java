package com.cargosfsr.inventario.auth;

import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

import jakarta.servlet.http.HttpSession;

@Component
@RequestScope
public class CurrentUser {

    private final HttpSession session;

    public CurrentUser(HttpSession session) {
        this.session = session;
    }

    public boolean isLoggedIn() {
        return username() != null;
    }

    public String username() {
        Object v = session.getAttribute("AUTH_USER_USERNAME");
        if (v == null) v = session.getAttribute("AUTH_USER_EMAIL"); // compatibilidad
        return v == null ? null : v.toString();
    }

    public String name() {
        Object v = session.getAttribute("AUTH_USER_NAME");
        return v == null ? null : v.toString();
    }

    public String role() {
        Object v = session.getAttribute("AUTH_USER_ROLE");
        return v == null ? null : v.toString();
    }

    public String display() {
        String n = name();
        return (n != null && !n.isBlank()) ? n : username();
    }
}
