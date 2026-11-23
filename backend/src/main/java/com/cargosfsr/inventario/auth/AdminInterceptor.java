package com.cargosfsr.inventario.auth;

import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

public class AdminInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) throws Exception {
        // Preflight CORS siempre pasa
        if ("OPTIONS".equalsIgnoreCase(req.getMethod())) { return true; }

        HttpSession s = req.getSession(false);
        if (s != null && "ADMIN".equals(String.valueOf(s.getAttribute("AUTH_USER_ROLE")))) {
            return true;
        }

        res.setStatus(403);
        res.setContentType("application/json;charset=UTF-8");
        res.getWriter().write("{\"message\":\"Requiere rol ADMIN\"}");
        return false;
    }

    // CORS se aplica centralmente mediante WebConfig, incluso para errores.
}
