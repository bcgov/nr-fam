package ca.bc.gov.nrs.fam.constants;

import io.swagger.v3.oas.annotations.media.Schema;

/** Identity provider as exposed on the external ({@code /external/v1}) API. *
 * <p>{@code enumAsRef} makes springdoc emit this as a named component schema
 * rather than inlining it into each property. Without it the generated
 * TypeScript client names the type after the property that uses it
 * ({@code FamAuthGrantDtoAuthKeyEnum}); the frontend imports these by name.
 */
@Schema(enumAsRef = true)
public enum IdpType {
  IDIR,
  /** Business BCeID. */
  BCEID,
  /** BC Services Card. */
  BCSC
}
