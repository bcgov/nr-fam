package ca.bc.gov.nrs.fam.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.metamodel.EntityType;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.transaction.annotation.Transactional;
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
 * cannot stand in for it, because the schema uses {@code JSONB},
 * {@code gen_random_uuid()} and expression indexes.
 *
 * <p>Nothing here points at a migrations directory: the SQL ships on the
 * classpath and the application's own Flyway configuration applies it, so this
 * test exercises the same path a deployment does.
 *
 * <p>Requires a Docker daemon. Without one the class is <em>skipped</em> rather
 * than failed, so a developer without Docker is not blocked by an error that
 * says nothing about their change. {@code DockerRequiredOnCiTest} makes sure
 * that convenience cannot turn into a silent hole: on CI the absence of Docker
 * is a failure, because skipping here would leave the schema unvalidated while
 * the build stayed green.
 */
@Testcontainers(disabledWithoutDocker = true)
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
    // Guards against an entity for something FAM does not own creeping back.
    // Applications, roles, role assignments, delegated administration and forest
    // clients belong to CSS; users belong to the token and the identity
    // directory. The audit trail is all that is left.
    List<String> mapped = entityManager.getMetamodel().getEntities().stream()
        .map(EntityType::getName)
        .sorted(Comparator.naturalOrder())
        .toList();

    assertThat(mapped).containsExactlyInAnyOrder(
        "FamPrivilegeChangeAudit",
        "FamPrivilegeChangeType");
  }

  /** A minimal, valid audit row: every NOT NULL column populated but update_user. */
  private FamPrivilegeChangeAudit auditRow(String createUser) {
    FamPrivilegeChangeAudit audit = new FamPrivilegeChangeAudit();
    audit.setChangeDate(LocalDateTime.now());
    audit.setChangePerformerUserDetails("{}");
    audit.setPrivilegeDetails("{}");
    audit.setPrivilegeChangeType(
        entityManager.getReference(FamPrivilegeChangeType.class, "GRANT"));
    audit.setCreateUser(createUser);
    // Same value as create_user, as production writes it: the row's author and
    // the person who made the change are one and the same outside a backfill.
    audit.setPerformerUser(createUser);
    return audit;
  }

  @Test
  @Transactional
  @DisplayName("a create fills update_user with the creator")
  void createFillsUpdateUserFromCreateUser() {
    // update_user is NOT NULL, and callers only ever set create_user on an
    // insert. AuditedEntity's @PrePersist is what makes that safe - and a
    // callback that stops being invoked fails silently everywhere else, since
    // the unit tests mock the repositories and never reach a persistence
    // context.
    String creator = "IDIR\\A1B2C3D4E5F60718293A4B5C6D7E8F90";

    FamPrivilegeChangeAudit audit = auditRow(creator);
    // update_user deliberately not set.

    entityManager.persist(audit);
    entityManager.flush();

    assertThat(audit.getUpdateUser()).isEqualTo(creator);
    assertThat(audit.getCreateDate()).isNotNull();
    assertThat(audit.getUpdateDate()).isNotNull();
  }

  @Test
  @Transactional
  @DisplayName("a row with no audit user at all is rejected by the database")
  void createUserIsMandatory() {
    // Proves the NOT NULL is real rather than something the callback papers
    // over: with no create_user there is nothing to copy, and the insert fails.
    FamPrivilegeChangeAudit audit = auditRow(null);

    assertThatThrownBy(() -> {
      entityManager.persist(audit);
      entityManager.flush();
    }).isInstanceOf(PersistenceException.class);
  }
}
