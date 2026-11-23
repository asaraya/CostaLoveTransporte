package com.cargosfsr.inventario.auth;

import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

public class AuthInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) throws Exception {
        String path = req.getRequestURI();

        // Preflight CORS debe pasar siempre
        if ("OPTIONS".equalsIgnoreCase(req.getMethod())) {
            // WebConfig aplica los encabezados CORS antes de este interceptor.
            return true;
        }

        // Endpoints públicos
        if (path.startsWith("/api/auth/")) return true;

        HttpSession session = req.getSession(false);
        if (session != null && session.getAttribute("AUTH_USER_ID") != null) return true;

        // 401 + CORS para que el frontend reciba la respuesta correctamente
        res.setStatus(401);
        res.setContentType("application/json;charset=UTF-8");
        res.getWriter().write("{\"message\":\"No autenticado\"}");
        return false;
    }

    // CORS se aplica centralmente mediante WebConfig, incluso para errores.
}
