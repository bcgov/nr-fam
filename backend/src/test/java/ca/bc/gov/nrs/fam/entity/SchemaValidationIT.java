package ca.bc.gov.nrs.fam.entity;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import jakarta.persistence.metamodel.EntityType;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Verifies the JPA entities against the schema the migration actually produces.
 *
 * <p>Flyway applies {@code db/migration} to a throwaway PostgreSQL container and
 * Hibernate then runs with {@code ddl-auto=validate}, so a column that is
 * mis-typed, mis-named or missing fails the build rather than the first
 * deployment. This is the only place the baseline meets a real PostgreSQL - H2
 * cannot stand in for it, because the schema uses {@code JSONB} and identity
 * columns.
 *
 * <p>Nothing here points at a migrations directory: the SQL ships on the
 * classpath and the application's own Flyway configuration applies it, so this
 * test exercises the same path a deployment does.
 *
 * <p>Requires a Docker daemon.
 */
@Testcontainers
@SpringBootTest
@DisplayName("JPA entities match the Flyway-migrated schema")
class SchemaValidationIT {

  @Container
  @SuppressWarnings("resource")
  static final PostgreSQLContainer<?> POSTGRES =
      // Matches the on-prem instance (18.x). This is the only place the baseline
      // meets a real PostgreSQL, so testing an older major than production runs
      // would be assurance about a database nothing deploys against.
      new PostgreSQLContainer<>("postgres:18-alpine")
          .withDatabaseName("fam")
          .withUsername("fam_owner")
          .withPassword("test");

  @Autowired
  private EntityManager entityManager;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);

    // Everything else - locations, schema, baseline-on-migrate - comes from
    // application.yml, so this runs what production runs.
    registry.add("spring.flyway.enabled", () -> true);
    registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
  }

  @Test
  @DisplayName("context starts, which means Hibernate validated every mapping")
  void contextLoadsWithSchemaValidation() {
    // Reaching this point means the baseline created what the entities expect:
    // Flyway migrated, then ddl-auto=validate passed for all of them.
    assertThat(entityManager).isNotNull();
  }

  @Test
  @DisplayName("only the four tables FAM still owns are mapped")
  void expectedEntitiesAreMapped() {
    // Guards against an entity for something that moved to CSS creeping back.
    // Applications, roles, role assignments, delegated administration and forest
    // clients are not FAM's to store any more.
    List<String> mapped = entityManager.getMetamodel().getEntities().stream()
        .map(EntityType::getName)
        .sorted(Comparator.naturalOrder())
        .toList();

    assertThat(mapped).containsExactlyInAnyOrder(
        "FamPrivilegeChangeAudit",
        "FamPrivilegeChangeType",
        "FamUser",
        "FamUserType");
  }
}
