package ca.bc.gov.nrs.fam.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The {@code @PrePersist} logic on its own.
 *
 * <p>That Hibernate actually <em>invokes</em> the callback is proved by
 * {@code SchemaValidationIT}, which needs Docker. This covers what the callback
 * decides, so a change in the rule is caught without one.
 */
@DisplayName("AuditedEntity (update_user defaulting)")
class AuditedEntityTest {

  /** Any concrete audited entity will do; the audit row is the only one left. */
  private static FamPrivilegeChangeAudit entity(String createUser, String updateUser) {
    FamPrivilegeChangeAudit user = new FamPrivilegeChangeAudit();
    user.setCreateUser(createUser);
    user.setUpdateUser(updateUser);
    return user;
  }

  @Test
  @DisplayName("fills update_user from create_user on a create")
  void fillsFromCreateUser() {
    FamPrivilegeChangeAudit user = entity("IDIR\\ABC", null);

    user.defaultUpdateUserToCreator();

    assertThat(user.getUpdateUser()).isEqualTo("IDIR\\ABC");
  }

  @Test
  @DisplayName("does not overwrite an update_user the caller set")
  void keepsAnExplicitUpdateUser() {
    // A flow that knows who is touching the row must win over the default.
    FamPrivilegeChangeAudit user = entity("IDIR\\ABC", "IDIR\\DEF");

    user.defaultUpdateUserToCreator();

    assertThat(user.getUpdateUser()).isEqualTo("IDIR\\DEF");
  }

  @Test
  @DisplayName("leaves update_user null when there is no creator to copy")
  void doesNotInventAnUpdateUser() {
    // The column is NOT NULL, so this insert must fail at the database rather
    // than be papered over with a placeholder that looks like a real value.
    FamPrivilegeChangeAudit user = entity(null, null);

    user.defaultUpdateUserToCreator();

    assertThat(user.getUpdateUser()).isNull();
  }
}
