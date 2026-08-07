package ca.bc.gov.nrs.fam.entity;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import jakarta.persistence.metamodel.EntityType;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
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
 * Verifies the JPA entities against the schema the real migrations produce.
 *
 * <p>This is the safety net for the SQLAlchemy-to-JPA port. Flyway applies
 * {@code migrations/sql} V1..V93 to a throwaway PostgreSQL container and
 * Hibernate then runs with {@code ddl-auto=validate}, so any column that was
 * mis-typed, mis-named or missed fails the build. Several discrepancies between
 * the upstream SQLAlchemy model and the actual DDL were found this way - for
 * example {@code fam_role.call_api_flag} is nullable in the schema despite the
 * model declaring it {@code nullable=False}.
 *
 * <p>Requires a Docker daemon.
 */
@Testcontainers
@SpringBootTest
@DisplayName("JPA entities match the Flyway-migrated schema")
class SchemaValidationIT {

  /** The seed-data migrations substitute an OIDC client id per application. */
  private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{(client_id_[a-z0-9_]+)}");

  private static final Path MIGRATIONS = Path.of("..", "migrations", "sql");

  @Container
  @SuppressWarnings("resource")
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:17-alpine")
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

    registry.add("spring.flyway.enabled", () -> true);
    registry.add("spring.flyway.locations",
        () -> "filesystem:" + MIGRATIONS.toAbsolutePath().normalize());
    registry.add("spring.flyway.schemas", () -> "app_fam");
    registry.add("spring.flyway.baseline-on-migrate", () -> false);

    // Supply a dummy value for every OIDC client-id placeholder the migrations
    // reference, so the seed data applies. Deployed environments supply real
    // Keycloak client ids and have no defaults.
    discoverPlaceholders().forEach(name ->
        registry.add("spring.flyway.placeholders." + name, () -> "test-" + name));

    // The schema is Flyway's; Hibernate only checks it.
    registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
  }

  private static Set<String> discoverPlaceholders() {
    try (Stream<Path> files = Files.list(MIGRATIONS)) {
      return files
          .filter(p -> p.getFileName().toString().endsWith(".sql"))
          .flatMap(SchemaValidationIT::placeholdersIn)
          .collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read " + MIGRATIONS.toAbsolutePath(), e);
    }
  }

  private static Stream<String> placeholdersIn(Path file) {
    try {
      Matcher matcher = PLACEHOLDER.matcher(Files.readString(file));
      return matcher.results().map(r -> r.group(1)).toList().stream();
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read " + file, e);
    }
  }

  @Test
  @DisplayName("context starts, which means Hibernate validated every mapping")
  void contextLoadsWithSchemaValidation() {
    // Reaching this point means ddl-auto=validate passed for all entities.
    assertThat(entityManager).isNotNull();
  }

  @Test
  @DisplayName("every FAM table the port covers is mapped")
  void expectedEntitiesAreMapped() {
    List<String> mapped = entityManager.getMetamodel().getEntities().stream()
        .map(EntityType::getName)
        .sorted(Comparator.naturalOrder())
        .toList();

    assertThat(mapped).containsExactlyInAnyOrder(
        "FamAccessControlPrivilege",
        "FamAppEnvironment",
        "FamApplication",
        "FamApplicationAdmin",
        "FamApplicationClient",
        "FamForestClient",
        "FamPrivilegeChangeAudit",
        "FamPrivilegeChangeType",
        "FamRole",
        "FamRoleType",
        "FamUser",
        "FamUserRoleXref",
        "FamUserTermsConditions",
        "FamUserType");
  }

  @Test
  @DisplayName("migrations seeded the code tables the entities depend on")
  void codeTablesAreSeeded() {
    assertThat(count("FamUserType")).isPositive();
    assertThat(count("FamRoleType")).isPositive();
    assertThat(count("FamAppEnvironment")).isPositive();
    // GRANT, REVOKE, UPDATE - seeded by V55.
    assertThat(count("FamPrivilegeChangeType")).isEqualTo(3);
  }

  private long count(String entityName) {
    return entityManager
        .createQuery("select count(e) from " + entityName + " e", Long.class)
        .getSingleResult();
  }
}
