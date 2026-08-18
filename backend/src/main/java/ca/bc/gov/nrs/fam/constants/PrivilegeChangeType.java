package ca.bc.gov.nrs.fam.constants;

import io.swagger.v3.oas.annotations.media.Schema;

/** {@code app_fam.fam_privilege_change_audit.privilege_change_type_code}. *
 * <p>{@code enumAsRef} makes springdoc emit this as a named component schema
 * rather than inlining it into each property. Without it the generated
 * TypeScript client names the type after the property that uses it
 * ({@code FamAuthGrantDtoAuthKeyEnum}); the frontend imports these by name.
 */
@Schema(enumAsRef = true)
public enum PrivilegeChangeType {
  GRANT,
  REVOKE,
  UPDATE,

  /**
   * A role was defined on an application.
   *
   * <p>The odd one out: every other type records a change to one person's access,
   * this one records a change to what the application's roles are. It therefore
   * has no target user, which is why those columns are nullable.
   *
   * <p>Longer than the ten characters the code column originally allowed - V95
   * widened it rather than contract the name.
   */
  CREATE_ROLE,

  /**
   * A role was removed from an application.
   *
   * <p>Like {@link #CREATE_ROLE}, a change to the application's roles rather than
   * to one person's access, so it carries no target user either. It is the one
   * change here that revokes access from several people at once - deleting a role
   * in Keycloak takes it away from everyone holding it - so the row records how
   * many held it, which is the only place that number survives.
   */
  DELETE_ROLE
}
