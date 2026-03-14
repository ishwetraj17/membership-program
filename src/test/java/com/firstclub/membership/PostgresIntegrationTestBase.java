package com.firstclub.membership;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base class for integration tests that require a live PostgreSQL database.
 *
 * <p>A single {@link PostgreSQLContainer} is started once for the whole test run
 * (static initialiser) and reused across all subclasses.  Spring's
 * {@link DynamicPropertySource} injects the container's JDBC URL / credentials
 * into the application context before the context is built, so they take
 * precedence over any profile-specific properties.
 *
 * <p>The container is started eagerly via a static initialiser (rather than the
 * {@code @Container} lifecycle annotation) so that it is guaranteed to be
 * running before Spring creates the application context — even when subclasses
 * use {@code @TestInstance(Lifecycle.PER_CLASS)}, which causes
 * {@code postProcessTestInstance} to run before {@code beforeAll}.
 *
 * <p>Flyway is intentionally disabled ({@code spring.flyway.enabled=false}) and
 * DDL is set to {@code create-drop} so that Hibernate creates the full schema from
 * JPA entities.  This keeps tests self-contained and independent of migration state.
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * class MyIntegrationTest extends PostgresIntegrationTestBase {
 *
 *     @Autowired
 *     private MyService myService;
 *
 *     @Test
 *     void myTest() { ... }
 * }
 * }</pre>
 *
 * Implemented by Shwet Raj
 */
@SpringBootTest(
        classes = MembershipApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
public abstract class PostgresIntegrationTestBase {

    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16")
                    .withDatabaseName("membershipdb")
                    .withUsername("membership_user")
                    .withPassword("membership_pass");

    static {
        POSTGRES.start();
    }

    /**
     * Overrides the datasource and JPA properties with values from the running
     * Testcontainers Postgres instance.  Called by Spring before the application
     * context is created — guarantees Postgres is used regardless of which
     * Spring profile is active.
     */
    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        // Let Hibernate create the schema from entities — keeps tests self-contained
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.flyway.enabled", () -> "false");
        // H2 console is irrelevant when Postgres is the datasource
        registry.add("spring.h2.console.enabled", () -> "false");
        // Reduce SQL noise in test output
        registry.add("logging.level.org.hibernate.SQL", () -> "WARN");
        registry.add("logging.level.org.hibernate.type.descriptor.sql.BasicBinder", () -> "WARN");
    }
}
