package ca.bc.gov.nrs.fam.constants;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The three kinds of administrator FAM's business rules distinguish.
 *
 * <p>The data model does not name these anywhere - they are derived from which
 * tables a user appears in:
 *
 * <ul>
 *   <li>{@code FAM_ADMIN} - has a {@code fam_application_admin} row for the FAM
 *       application itself, and so may administer every application;
 *   <li>{@code APP_ADMIN} - has {@code fam_application_admin} rows for other
 *       applications;
 *   <li>{@code DELEGATED_ADMIN} - has {@code fam_access_control_privilege} rows,
 *       granting authority over specific roles.
 * </ul>
 *
 * <p>Ported from {@code admin_management/api/app/constants.py}.
 *
 * <p>{@code enumAsRef} makes springdoc emit this as a named component schema
 * rather than inlining it into each property. Without it the generated
 * TypeScript client names the type after the property that uses it
 * ({@code FamAuthGrantDtoAuthKeyEnum}); the frontend imports these by name.
 */
@Schema(enumAsRef = true)
public enum AdminRoleAuthGroup {
  FAM_ADMIN,
  APP_ADMIN,
  DELEGATED_ADMIN
}
