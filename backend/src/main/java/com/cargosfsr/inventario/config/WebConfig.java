package com.cargosfsr.inventario.config;

import java.util.Arrays;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.cargosfsr.inventario.auth.AdminInterceptor;
import com.cargosfsr.inventario.auth.AuthInterceptor;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private static final String FRONT_SANPABLO = "https://cargofsr-production.up.railway.app";
    private static final String FRONT_GUAPILES = "https://costalovetransporte-production.up.railway.app";
    private static final String FRONT_DEV      = "http://localhost:5173";

    @Bean
    public BCryptPasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    /* CORS por WebMvc (handlers) */
    @Override
    public void addCorsMappings(CorsRegistry registry) {

        // Mantiene /api/** como lo tenías
        registry.addMapping("/api/**")
                .allowedOrigins(FRONT_SANPABLO, FRONT_GUAPILES, FRONT_DEV)
                .allowedMethods("GET","POST","PUT","PATCH","DELETE","OPTIONS")
                .allowedHeaders("Content-Type","Accept","X-Requested-With","Authorization","Origin","Cache-Control","Pragma")
                .exposedHeaders("Location")
                .allowCredentials(true)
                .maxAge(3600);

        // NUEVO: agrega CORS para /auth/** (porque estás pegándole a /auth/login)
        registry.addMapping("/auth/**")
                .allowedOrigins(FRONT_SANPABLO, FRONT_GUAPILES, FRONT_DEV)
                .allowedMethods("GET","POST","PUT","PATCH","DELETE","OPTIONS")
                .allowedHeaders("Content-Type","Accept","X-Requested-With","Authorization","Origin","Cache-Control","Pragma")
                .exposedHeaders("Location")
                .allowCredentials(true)
                .maxAge(3600);
    }

    /* CORS global a nivel de filtro (pasa preflight antes de interceptores) */
    @Bean
    public FilterRegistrationBean<CorsFilter> corsFilter() {
        CorsConfiguration cfg = new CorsConfiguration();

        // Mantiene anteriores + agrega el nuevo
        cfg.setAllowedOrigins(Arrays.asList(FRONT_SANPABLO, FRONT_GUAPILES, FRONT_DEV));

        cfg.setAllowCredentials(true);
        cfg.setAllowedMethods(Arrays.asList("GET","POST","PUT","PATCH","DELETE","OPTIONS"));
        cfg.setAllowedHeaders(Arrays.asList("Content-Type","Accept","X-Requested-With","Authorization","Origin","Cache-Control","Pragma"));
        cfg.setExposedHeaders(Arrays.asList("Location"));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();

        // Mantiene /api/**
        source.registerCorsConfiguration("/api/**", cfg);

        // NUEVO: agrega /auth/**
        source.registerCorsConfiguration("/auth/**", cfg);

        // (Opcional pero útil) si tienes endpoints fuera de /api y /auth
        // source.registerCorsConfiguration("/**", cfg);

        FilterRegistrationBean<CorsFilter> bean = new FilterRegistrationBean<>(new CorsFilter(source));
        bean.setOrder(0); // más alto que cualquier interceptor
        return bean;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AuthInterceptor())
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/auth/**");

        registry.addInterceptor(new AdminInterceptor())
                .addPathPatterns("/api/admin/**");
    }
}
