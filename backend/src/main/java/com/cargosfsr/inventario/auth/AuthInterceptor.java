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
            // Añade CORS al preflight por si no pasa por el mapeo global (defensivo)
            addCorsHeaders(req, res);
            return true;
        }

        // Endpoints públicos
        if (path.startsWith("/api/auth/")) return true;

        HttpSession session = req.getSession(false);
        if (session != null && session.getAttribute("AUTH_USER_ID") != null) return true;

        // 401 + CORS para que el frontend reciba la respuesta correctamente
        addCorsHeaders(req, res);
        res.setStatus(401);
        res.setContentType("application/json;charset=UTF-8");
        res.getWriter().write("{\"message\":\"No autenticado\"}");
        return false;
    }

    private void addCorsHeaders(HttpServletRequest req, HttpServletResponse res) {
        String origin = req.getHeader("Origin");
        if (origin != null &&
            (origin.equals("https://cargofsr-production.up.railway.app")
            || origin.equals("http://localhost:5173"))) {
            res.setHeader("Access-Control-Allow-Origin", origin);
            res.setHeader("Vary", "Origin");
        }
        res.setHeader("Access-Control-Allow-Credentials", "true");
        res.setHeader("Access-Control-Allow-Methods", "GET,POST,PUT,PATCH,DELETE,OPTIONS");

        // ECO de los headers solicitados, con fallback explícito
        String reqHeaders = req.getHeader("Access-Control-Request-Headers");
        if (reqHeaders != null && !reqHeaders.isBlank()) {
            res.setHeader("Access-Control-Allow-Headers", reqHeaders);
        } else {
            res.setHeader("Access-Control-Allow-Headers",
                "Content-Type, Accept, X-Requested-With, Authorization, Origin, Cache-Control, Pragma");
        }

        res.setHeader("Access-Control-Expose-Headers", "Location");
    }
}
