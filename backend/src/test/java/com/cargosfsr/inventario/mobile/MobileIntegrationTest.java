package com.cargosfsr.inventario.mobile;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.time.Instant;
import java.util.UUID;
import com.cargosfsr.inventario.MysqlPruebas;
import com.cargosfsr.inventario.auth.CurrentUser;
import com.cargosfsr.inventario.config.WebConfig;
import com.cargosfsr.inventario.services.EstadoService;
import com.cargosfsr.inventario.services.PaqueteAuditService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@EnabledIfSystemProperty(named = "test.mobile.mysql.url", matches = ".+")
@SpringBootTest(classes = MobileIntegrationTest.Config.class)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MobileIntegrationTest {
    @Configuration
    @EnableAutoConfiguration
    @EntityScan("com.cargosfsr.inventario.model")
    @EnableJpaRepositories("com.cargosfsr.inventario.repository")
    @Import({MobileController.class, MobileAuth.class, MobilePodService.class, MobileErrors.class,
            MobileWebConfig.class, WebConfig.class, EstadoService.class, PaqueteAuditService.class})
    static class Config {
        @Bean CurrentUser currentUser() { return new CurrentUser(new MockHttpSession()); }
    }

    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties) {
        String url = System.getProperty("test.mobile.mysql.url");
        assertTrue(url.matches("jdbc:mysql://127[.]0[.]0[.]1:34117/mobile_test_heredia([?].*)?"));
        properties.add("spring.datasource.url", () -> url);
        properties.add("spring.datasource.username", () -> "root");
        properties.add("spring.datasource.password", () -> "");
        properties.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired BCryptPasswordEncoder encoder;
    @Autowired ObjectMapper json;
    @Autowired MobilePodService pods;

    @BeforeAll void migrate() throws Exception {
        MysqlPruebas.migrar(jdbc);
        MysqlPruebas.migrar(jdbc);
        MysqlPruebas.ejecutarScript(jdbc, java.nio.file.Path.of("src/test/resources/legacy_conteos.sql"));
    }

    @BeforeEach void fixture() {
        jdbc.update("INSERT INTO usuarios(id,username,full_name,password,rol) VALUES(1,'mensajero1','Ana',?,'MENSAJERO'),(2,'mensajero2','Luis',?,'MENSAJERO')",
                encoder.encode("test-password"), encoder.encode("test-password"));
        jdbc.update("INSERT INTO sacos(id,marchamo) VALUES(1,'PRUEBA')");
        jdbc.update("INSERT INTO distritos(id,nombre) VALUES(1,'PRUEBA')");
        jdbc.update("INSERT INTO ubicacion(id,tipo,codigo) VALUES(1,'MUEBLE','PRUEBA')");
        jdbc.update("INSERT INTO paquetes(id,tracking_code,saco_id,distrito_id,ubicacion_id,received_at) VALUES(1,'LM123',1,1,1,'2026-11-01 08:00:00')");
    }

    String login(String username) throws Exception {
        var response = mvc.perform(post("/api/mobile/v1/login").contentType("application/json")
                .content(json.writeValueAsString(new MobileController.Login(username, "test-password"))))
                .andExpect(status().isOk()).andReturn();
        return "Bearer " + json.readTree(response.getResponse().getContentAsString()).get("token").asText();
    }

    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder pod(String operation, String version) {
        byte[] jpeg = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 1, 2, 3};
        return multipart("/api/mobile/v1/pods")
                .file(new MockMultipartFile("parcel", "parcel.jpg", "image/jpeg", jpeg))
                .file(new MockMultipartFile("recipient", "id.jpg", "image/jpeg", jpeg))
                .param("operationId", operation).param("tracking", "LM123")
                .param("capturedAt", "2026-11-12T17:47:00Z").param("version", version);
    }

    @Test @Transactional void deliveryUsesAuthenticatedUserAndRetriesDoNotDuplicate() throws Exception {
        String token = login("mensajero1");
        String version = pods.lookup("LM123").get("version").toString();
        String id = UUID.randomUUID().toString();
        mvc.perform(pod(id, version).header("Authorization", token).param("usuarioId", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CONFIRMED"));
        mvc.perform(pod(id, version).header("Authorization", token)).andExpect(status().isOk());
        assertEquals(1L, jdbc.queryForObject("SELECT mensajero_id FROM paquetes WHERE id=1", Long.class));
        assertEquals("PRUEBA_DE_ENTREGA", jdbc.queryForObject("SELECT estado FROM paquetes WHERE id=1", String.class));
        assertEquals("2026-11-12 11:47:00", jdbc.queryForObject("SELECT DATE_FORMAT(delivered_at,'%Y-%m-%d %H:%i:%s') FROM paquetes WHERE id=1", String.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM mobile_pods", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM paquete_estado_historial", Integer.class));
        assertEquals("APLICACION_MOVIL", jdbc.queryForObject("SELECT modulo_origen FROM paquete_audit_log", String.class));
        assertEquals("mensajero1", jdbc.queryForObject("SELECT usuario FROM paquete_audit_log", String.class));
        mvc.perform(pod(id, version).header("Authorization", login("mensajero2"))).andExpect(status().isConflict());
        mvc.perform(get("/api/mobile/v1/reports/deliveries.xlsx").header("Authorization", token)
                .param("from", "2026-11-12").param("to", "2026-11-12"))
                .andExpect(status().isOk()).andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
    }

    @Test @Transactional void rejectsStalePackageAndRequiresMobileSession() throws Exception {
        mvc.perform(get("/api/mobile/v1/packages/LM123")).andExpect(status().isUnauthorized());
        String token = login("mensajero1");
        mvc.perform(pod(UUID.randomUUID().toString(), "old-version").header("Authorization", token))
                .andExpect(status().isConflict());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM mobile_pods", Integer.class));
        assertEquals("ENTREGADO_A_TRANSPORTISTA_LOCAL", jdbc.queryForObject("SELECT estado FROM paquetes WHERE id=1", String.class));
        mvc.perform(post("/api/mobile/v1/logout").header("Authorization", token)).andExpect(status().isOk());
        mvc.perform(get("/api/mobile/v1/packages/LM123").header("Authorization", token)).andExpect(status().isUnauthorized());
    }
}
