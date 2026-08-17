package ca.bc.gov.nrs.fam.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * A new role to define on a CSS integration.
 *
 * <p>The two scope flags are checkboxes rather than a scope-type field because
 * that is how the screen asks the question. They are mutually exclusive - see
 * {@link #scopeType()}.
 */
public record CssRoleCreateRequest(
    /**
     * The raw code, e.g. {@code FREP_ADMINISTRATOR}.
     *
     * <p>This becomes the role name, which is what reaches the access token and
     * what applications authorise on. Upper cased before validation, so a lower
     * case entry is accepted rather than rejected on a technicality.
     */
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "FREP_ADMINISTRATOR")
    @NotBlank String roleCode,

    /**
     * The human-readable description, e.g. {@code FREP Administrator}.
     *
     * <p>Free text, stored on a sidecar role - see
     * {@link CssRoleNaming#LABEL_PREFIX}. Bounded well below Keycloak's 255
     * character limit for a role name, which the sidecar has to fit inside
     * alongside the prefix and the code.
     */
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "FREP Administrator")
    @NotBlank @Size(max = 150) String description,

    /** Granting this role requires choosing one or more districts. */
    boolean requiresDistrict,

    /** Granting this role requires choosing one or more forest clients. */
    boolean requiresForestClient) {

  /**
   * The single scope type these flags describe, or null for an unscoped role.
   *
   * <p>Both at once is refused rather than resolved. A grant carries one
   * {@code scope_type} and the picker offers one kind of scope, so a role marked
   * both would silently be treated as district scoped and its forest client side
   * would be unreachable.
   */
  public String scopeType() {
    if (requiresDistrict && requiresForestClient) {
      throw new IllegalArgumentException(
          "A role is scoped by district or by forest client, not both.");
    }
    if (requiresDistrict) {
      return "DISTRICT";
    }
    return requiresForestClient ? "FOREST_CLIENT" : null;
  }
}
