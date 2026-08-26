package ca.bc.gov.nrs.fam.dto;

import java.util.ArrayList;
import java.util.List;
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
     * The short human-readable name, e.g. {@code View All}.
     *
     * <p>What an administrator sees when granting the role, and what the
     * permission pills show. Free text, stored on a sidecar role - see
     * {@link CssRoleNaming#LABEL_PREFIX}. Bounded well below Keycloak's 255
     * character limit for a role name, which the sidecar has to fit inside
     * alongside the prefix and the code.
     */
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "View All")
    @NotBlank @Size(max = 150) String roleName,

    /**
     * The long description, e.g. {@code Allows users to view all the FSPs but
     * not edit}.
     *
     * <p>Optional: a role whose name says enough needs no sentence. Stored on a
     * second sidecar - see {@link CssRoleNaming#DESCRIPTION_PREFIX} - because a
     * sentence and a name will not reliably fit in one role name together.
     *
     * <p><b>180, because the sidecar has to fit inside a Keycloak role name.</b>
     * That name is {@code FAM:DESC:<code>:<description>} and Keycloak allows 255
     * characters, so the budget is 255 - 9 for the prefix - 1 for the separator
     * - up to 59 for the code, leaving 186. This was 200, which the two fields
     * being bounded independently made look safe: a code longer than 45
     * characters plus a long description composed a name Keycloak refused, as an
     * opaque upstream error naming neither field, after the role itself had
     * already been created.
     *
     * <p>180 rather than 186 so the arithmetic has somewhere to move. Anything
     * that lengthens the prefix or the code has to be checked against this.
     */
    @Schema(example = "Allows users to view all the FSPs but not edit")
    @Size(max = 180) String description,

    /** Granting this role requires choosing one or more districts. */
    boolean requiresDistrict,

    /** Granting this role requires choosing one or more regions. */
    boolean requiresRegion,

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
  /**
   * Every scope this role is defined by, in no particular order - the caller
   * canonicalises.
   *
   * <p>A role may require both. A submitter for a district <em>and</em> a forest
   * client is granted against the pair, and its generated role name carries both
   * suffixes. This used to throw when both were ticked, back when a grant could
   * only carry one scope type.
   */
  public List<String> scopeTypes() {
    List<String> types = new ArrayList<>();
    if (requiresDistrict) {
      types.add(CssRoleNaming.SCOPE_DISTRICT);
    }
    if (requiresRegion) {
      types.add(CssRoleNaming.SCOPE_REGION);
    }
    if (requiresForestClient) {
      types.add(CssRoleNaming.SCOPE_FOREST_CLIENT);
    }
    return List.copyOf(types);
  }
}
