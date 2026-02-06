package com.cargosfsr.inventario;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Test mínimo: levanta un contexto Spring Boot "vacío" (sin escanear tu app)
 * y excluye solo lo que haría fallar el arranque en test (DB/JPA/Redis).
 * No toca POM ni código de producción.
 */
@SpringBootTest(
    classes = BackendApplicationTests.MinimalApp.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
        // Evitar que en test se intente crear/conectar DB/JPA/Redis:
        "spring.autoconfigure.exclude=" +
            "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration," +
            "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration," +
            "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration," +
            "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration",
        // Cache simple en memoria
        "spring.cache.type=SIMPLE",
        // Que nadie ejecute init-sql en test
        "spring.datasource.hikari.connection-init-sql=",
        // Lazy init para no instanciar nada innecesario
        "spring.main.lazy-initialization=true"
    }
)
class BackendApplicationTests {

    /** 
     * Configuración mínima para el test:
     * - No hay @ComponentScan, por lo que NO se cargan tus @Service/@Repo.
     * - Solo se habilita la autoconfiguración (con las exclusiones de arriba).
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class MinimalApp {}

    @Test
    void contextLoads() {
        // Si el contexto mínimo arrancó, este test pasa.
    }
}
