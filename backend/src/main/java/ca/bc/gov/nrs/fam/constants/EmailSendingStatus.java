package ca.bc.gov.nrs.fam.constants;

import io.swagger.v3.oas.annotations.media.Schema;

/** Outcome of the GC Notify call made alongside a grant. *
 * <p>{@code enumAsRef} makes springdoc emit this as a named component schema
 * rather than inlining it into each property. Without it the generated
 * TypeScript client names the type after the property that uses it
 * ({@code FamAuthGrantDtoAuthKeyEnum}); the frontend imports these by name.
 */
@Schema(enumAsRef = true)
public enum EmailSendingStatus {
  /** The grant did not call for an email. */
  NOT_REQUIRED,
  SENT_TO_EMAIL_SERVICE_SUCCESS,
  /** Technical or validation failure while handing off to GC Notify. */
  SENT_TO_EMAIL_SERVICE_FAILURE
}
