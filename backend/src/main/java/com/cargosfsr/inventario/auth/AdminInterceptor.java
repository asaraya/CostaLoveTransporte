package com.cargosfsr.inventario.auth;

import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

public class AdminInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) throws Exception {
        // Preflight CORS siempre pasa
        if ("OPTIONS".equalsIgnoreCase(req.getMethod())) { addCors(req, res); return true; }

        HttpSession s = req.getSession(false);
        if (s != null && "ADMIN".equals(String.valueOf(s.getAttribute("AUTH_USER_ROLE")))) {
            return true;
        }

        addCors(req, res);
        res.setStatus(403);
        res.setContentType("application/json;charset=UTF-8");
        res.getWriter().write("{\"message\":\"Requiere rol ADMIN\"}");
        return false;
    }

    private void addCors(HttpServletRequest req, HttpServletResponse res) {
        String origin = req.getHeader("Origin");
        if ("https://cargofsr-production.up.railway.app".equals(origin) || "http://localhost:5173".equals(origin)) {
            res.setHeader("Access-Control-Allow-Origin", origin);
            res.setHeader("Vary", "Origin");
        }
        res.setHeader("Access-Control-Allow-Credentials", "true");
        res.setHeader("Access-Control-Allow-Methods", "GET,POST,PUT,PATCH,DELETE,OPTIONS");
        String reqHeaders = req.getHeader("Access-Control-Request-Headers");
        res.setHeader("Access-Control-Allow-Headers",
            (reqHeaders != null && !reqHeaders.isBlank()) ? reqHeaders
            : "Content-Type, Accept, X-Requested-With, Authorization, Origin, Cache-Control, Pragma");
        res.setHeader("Access-Control-Expose-Headers", "Location");
    }
}
