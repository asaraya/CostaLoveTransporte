package com.cargosfsr.inventario.mobile;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.time.Instant;
import java.util.UUID;
import com.cargosfsr.inventario.MysqlPruebas;
import com.cargosfsr.inventario.auth.CurrentUser;
import com.cargosfsr.inventario.auth.AdminController;
import com.cargosfsr.inventario.controllers.ApiExceptionHandler;
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
            MobileWebConfig.class, WebConfig.class, EstadoService.class, PaqueteAuditService.class,
            AdminController.class, ApiExceptionHandler.class})
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
    @Autowired jakarta.persistence.EntityManager em;
    private Long courier1;
    private Long courier2;

    @BeforeAll void migrate() throws Exception {
        MysqlPruebas.migrar(jdbc);
        MysqlPruebas.migrar(jdbc);
        var access = java.nio.file.Path.of("../database/acceso_mensajeros.sql");
        MysqlPruebas.ejecutarScript(jdbc, access);
        var original = jdbc.queryForMap("SELECT id, password, rol, role, active FROM usuarios WHERE username='prueba'");
        MysqlPruebas.ejecutarScript(jdbc, access);
        assertEquals(original, jdbc.queryForMap("SELECT id, password, rol, role, active FROM usuarios WHERE username='prueba'"));
        var singlePhoto = java.nio.file.Path.of("../database/pod_foto_unica.sql");
        MysqlPruebas.ejecutarScript(jdbc, singlePhoto);
        MysqlPruebas.ejecutarScript(jdbc, singlePhoto);
        var attempts = java.nio.file.Path.of("../database/intentos_movil.sql");
        MysqlPruebas.ejecutarScript(jdbc, attempts);
        MysqlPruebas.ejecutarScript(jdbc, attempts);
        MysqlPruebas.ejecutarScript(jdbc, java.nio.file.Path.of("src/test/resources/legacy_conteos.sql"));
    }

    @BeforeEach void fixture() {
        jdbc.update("INSERT INTO usuarios(username,full_name,password,rol) VALUES('mensajero1','Ana',?,'MENSAJERO'),('mensajero2','Luis',?,'MENSAJERO')",
                encoder.encode("test-password"), encoder.encode("test-password"));
        courier1 = jdbc.queryForObject("SELECT id FROM usuarios WHERE username='mensajero1'", Long.class);
        courier2 = jdbc.queryForObject("SELECT id FROM usuarios WHERE username='mensajero2'", Long.class);
        jdbc.update("INSERT INTO sacos(id,marchamo) VALUES(1,'PRUEBA')");
        jdbc.update("INSERT INTO distritos(id,nombre) VALUES(1,'PRUEBA')");
        jdbc.update("INSERT INTO ubicacion(id,tipo,codigo) VALUES(1,'MUEBLE','PRUEBA')");
        jdbc.update("INSERT INTO paquetes(id,tracking_code,saco_id,distrito_id,ubicacion_id,received_at) VALUES(1,'LM123',1,1,1,'2026-11-01 08:00:00')");
    }

    String login(String username) throws Exception {
        return login(username, "test-password");
    }

    String login(String username, String password) throws Exception {
        var response = mvc.perform(post("/api/mobile/v1/login").contentType("application/json")
                .content(json.writeValueAsString(new MobileController.Login(username, password))))
                .andExpect(status().isOk()).andReturn();
        return "Bearer " + json.readTree(response.getResponse().getContentAsString()).get("token").asText();
    }

    MockHttpSession adminSession() {
        var session = new MockHttpSession();
        session.setAttribute("AUTH_USER_ID", 99L);
        session.setAttribute("AUTH_USER_ROLE", "ADMIN");
        return session;
    }

    @Test @Transactional void scriptCreatesSingleTrialCourierWithRequestedPassword() throws Exception {
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM usuarios WHERE username='prueba'", Integer.class));
        String hash = jdbc.queryForObject("SELECT password FROM usuarios WHERE username='prueba'", String.class);
        assertNotEquals("12345", hash);
        assertTrue(encoder.matches("12345", hash));
        String token = login("prueba", "12345");
        mvc.perform(get("/api/mobile/v1/me").header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.username").value("prueba"));
    }

    @Test @Transactional void createsCourierWithHashedPasswordAndMobileAccess() throws Exception {
        var response = mvc.perform(post("/api/admin/mensajeros").session(adminSession()).contentType("application/json")
                .content("{\"fullName\":\"Mensajero nuevo\",\"username\":\"Nuevo.User\",\"password\":\"clave-segura\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.user.username").value("nuevo.user"))
                .andExpect(jsonPath("$.user.rol").value("MENSAJERO"))
                .andExpect(jsonPath("$.user.password").doesNotExist()).andReturn();
        long id = json.readTree(response.getResponse().getContentAsString()).path("user").path("id").asLong();
        String hash = jdbc.queryForObject("SELECT password FROM usuarios WHERE id=?", String.class, id);
        assertNotEquals("clave-segura", hash);
        assertTrue(encoder.matches("clave-segura", hash));
        String token = login("nuevo.user", "clave-segura");
        mvc.perform(get("/api/mobile/v1/me").header("Authorization", token)).andExpect(status().isOk());
    }

    @Test @Transactional void assignsPasswordToLegacyCourierWithoutReplacingIdentityOrDeliveries() throws Exception {
        jdbc.update("UPDATE usuarios SET rol='OPERADOR', role='TRANSPORTISTA LOCAL' WHERE id=?", courier1);
        jdbc.update("UPDATE paquetes SET mensajero_id=? WHERE id=1", courier1);
        String token = login("mensajero1");
        mvc.perform(post("/api/admin/mensajeros/{id}/password", courier1).session(adminSession()).contentType("application/json")
                .content("{\"password\":\"clave-nueva\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.user.username").value("mensajero1"));
        em.clear();
        assertEquals(courier1, jdbc.queryForObject("SELECT mensajero_id FROM paquetes WHERE id=1", Long.class));
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM usuarios", Integer.class));
        mvc.perform(get("/api/mobile/v1/me").header("Authorization", token)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/mobile/v1/login").contentType("application/json")
                .content("{\"username\":\"mensajero1\",\"password\":\"test-password\"}"))
                .andExpect(status().isUnauthorized());
        login("mensajero1", "clave-nueva");
    }

    @Test @Transactional void rejectsMissingCredentialsAndDuplicateUsernames() throws Exception {
        mvc.perform(post("/api/admin/mensajeros").session(adminSession()).contentType("application/json")
                .content("{\"fullName\":\"Sin clave\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/admin/mensajeros").session(adminSession()).contentType("application/json")
                .content("{\"fullName\":\"Otro\",\"username\":\"mensajero1\",\"password\":\"clave-segura\"}"))
                .andExpect(status().isBadRequest());
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM usuarios", Integer.class));
    }

    @Test @Transactional void onlyAdminCanAssignPasswordAndOnlyToCouriers() throws Exception {
        var request = post("/api/admin/mensajeros/{id}/password", courier1).contentType("application/json")
                .content("{\"password\":\"clave-nueva\"}");
        mvc.perform(request).andExpect(status().isUnauthorized());
        var nonAdmin = new MockHttpSession();
        nonAdmin.setAttribute("AUTH_USER_ID", courier1);
        nonAdmin.setAttribute("AUTH_USER_ROLE", "TRANSPORTISTA");
        mvc.perform(post("/api/admin/mensajeros/{id}/password", courier1).session(nonAdmin).contentType("application/json")
                .content("{\"password\":\"clave-nueva\"}"))
                .andExpect(status().isForbidden());
        jdbc.update("UPDATE usuarios SET rol='OPERADOR',role='ADMIN' WHERE id=?", courier2);
        mvc.perform(post("/api/admin/mensajeros/{id}/password", courier2).session(adminSession()).contentType("application/json")
                .content("{\"password\":\"clave-nueva\"}"))
                .andExpect(status().isBadRequest());
        assertTrue(encoder.matches("test-password", jdbc.queryForObject("SELECT password FROM usuarios WHERE id=?", String.class, courier2)));
    }

    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder pod(String operation, String version) {
        byte[] jpeg = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 1, 2, 3};
        return multipart("/api/mobile/v1/pods")
                .file(new MockMultipartFile("parcel", "parcel.jpg", "image/jpeg", jpeg))
                .file(new MockMultipartFile("recipient", "id.jpg", "image/jpeg", jpeg))
                .param("operationId", operation).param("tracking", "LM123")
                .param("capturedAt", "2026-11-12T17:47:00Z").param("version", version);
    }

    @Test @Transactional void singlePhotoPodUpdatesDeliveryOnceAndStoresNoDuplicatePhoto() throws Exception {
        String token = login("mensajero1");
        String version = pods.lookup("LM123").get("version").toString();
        String operation = UUID.randomUUID().toString();
        byte[] jpeg = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 4, 5, 6};
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(multipart("/api/mobile/v1/pods")
                    .file(new MockMultipartFile("proof", "pod.jpg", "image/jpeg", jpeg))
                    .param("operationId", operation).param("tracking", "LM123")
                    .param("capturedAt", "2026-11-12T17:47:00Z").param("version", version)
                    .header("Authorization", token))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CONFIRMED"));
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM mobile_pods", Integer.class));
        assertArrayEquals(jpeg, jdbc.queryForObject("SELECT parcel_photo FROM mobile_pods", byte[].class));
        assertNull(jdbc.queryForObject("SELECT recipient_photo FROM mobile_pods", byte[].class));
        assertEquals(courier1, jdbc.queryForObject("SELECT mensajero_id FROM paquetes WHERE id=1", Long.class));
        assertEquals("PRUEBA_DE_ENTREGA", jdbc.queryForObject("SELECT estado FROM paquetes WHERE id=1", String.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM paquete_estado_historial", Integer.class));
    }

    @Test @Transactional void deliveryUsesAuthenticatedUserAndRetriesDoNotDuplicate() throws Exception {
        String token = login("mensajero1");
        String version = pods.lookup("LM123").get("version").toString();
        String id = UUID.randomUUID().toString();
        mvc.perform(pod(id, version).header("Authorization", token).param("usuarioId", courier2.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CONFIRMED"));
        mvc.perform(pod(id, version).header("Authorization", token)).andExpect(status().isOk());
        assertEquals(courier1, jdbc.queryForObject("SELECT mensajero_id FROM paquetes WHERE id=1", Long.class));
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

    @Test @Transactional void attemptsProgressOncePerOperationWithoutRecordingDelivery() throws Exception {
        jdbc.update("UPDATE paquetes SET estado='NO_ENTREGADO_CONSIGNATARIO_DISPONIBLE' WHERE id=1");
        em.clear();
        String token = login("mensajero1");
        String secondToken = login("mensajero2");
        String[] expected = {"ENTREGADO_A_TRANSPORTISTA_LOCAL", "ENTREGADO_A_TRANSPORTISTA_LOCAL_2DO_INTENTO"};
        for (int index = 0; index < 2; index++) {
            String version = pods.lookup("LM123").get("version").toString();
            String body = json.writeValueAsString(new MobileController.Attempt(UUID.randomUUID().toString(), "LM123",
                    Instant.parse(index == 0 ? "2026-11-12T17:47:00Z" : "2026-11-13T17:47:00Z"), version));
            for (int retry = 0; retry < 2; retry++) {
                mvc.perform(post("/api/mobile/v1/attempts").header("Authorization", token)
                        .contentType("application/json").content(body)).andExpect(status().isOk())
                        .andExpect(jsonPath("$.state").value(expected[index]))
                        .andExpect(jsonPath("$.version").value(pods.lookup("LM123").get("version").toString()));
            }
            mvc.perform(post("/api/mobile/v1/attempts").header("Authorization", secondToken)
                    .contentType("application/json").content(body)).andExpect(status().isConflict());
        }
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM mobile_attempts", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM paquete_estado_historial", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM paquete_audit_log WHERE modulo_origen='APLICACION_MOVIL' AND usuario='mensajero1'", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM mobile_pods", Integer.class));
        assertNull(jdbc.queryForObject("SELECT delivered_at FROM paquetes WHERE id=1", java.sql.Timestamp.class));
        assertEquals(courier1, jdbc.queryForObject("SELECT MIN(usuario_id) FROM mobile_attempts", Long.class));
        String third = json.writeValueAsString(new MobileController.Attempt(UUID.randomUUID().toString(), "LM123",
                Instant.parse("2026-11-14T17:47:00Z"), pods.lookup("LM123").get("version").toString()));
        mvc.perform(post("/api/mobile/v1/attempts").header("Authorization", token)
                .contentType("application/json").content(third)).andExpect(status().isConflict());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM mobile_attempts", Integer.class));
    }

    @Test @Transactional void attemptRequiresSessionAndRejectsStaleOrDeliveredPackages() throws Exception {
        String body = json.writeValueAsString(new MobileController.Attempt(UUID.randomUUID().toString(), "LM123",
                Instant.parse("2026-11-12T17:47:00Z"), "stale"));
        mvc.perform(post("/api/mobile/v1/attempts").contentType("application/json").content(body))
                .andExpect(status().isUnauthorized());
        String token = login("mensajero1");
        mvc.perform(post("/api/mobile/v1/attempts").header("Authorization", token)
                .contentType("application/json").content(body)).andExpect(status().isConflict());
        jdbc.update("UPDATE paquetes SET estado='PRUEBA_DE_ENTREGA', mensajero_id=?, delivered_at='2026-11-12 11:47:00' WHERE id=1", courier1);
        em.clear();
        body = json.writeValueAsString(new MobileController.Attempt(UUID.randomUUID().toString(), "LM123",
                Instant.parse("2026-11-12T17:47:00Z"), pods.lookup("LM123").get("version").toString()));
        mvc.perform(post("/api/mobile/v1/attempts").header("Authorization", token)
                .contentType("application/json").content(body)).andExpect(status().isConflict());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM mobile_attempts", Integer.class));
    }
}
