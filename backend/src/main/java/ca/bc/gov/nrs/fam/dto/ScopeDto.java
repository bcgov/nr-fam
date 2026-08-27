package ca.bc.gov.nrs.fam.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One dimension a granted role is scoped by, as the screens display it.
 *
 * <p>A role may carry more than one - a submitter for a district <em>and</em> a
 * forest client - so rows carry a list of these rather than a type/value pair.
 * Each renders as its own chip.
 *
 * <p>{@code label} is the readable name where one is known: a district's name or
 * a forest client's, resolved when the listing is enriched. Null falls back to
 * the value, which is what a row shows before enrichment or when the upstream
 * lookup is unavailable.
 */
public record ScopeDto(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "DISTRICT") String type,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "DCC") String value,
    String label) {

  /**
   * A scope as it should read, from a scope as it is encoded in a role name.
   *
   * <p>The label is what the screens show and the value is what the grant was
   * made against, so the two must both be here: a chip reading
   * {@code KOOTENAY_BOUNDARY} is a code leaking through, and a chip reading only
   * {@code Kootenay-Boundary} loses the thing an application authorises on.
   *
   * <p>Only regions get one. Their names are a constant in FAM - see
   * {@link ca.bc.gov.nrs.fam.constants.Region} - so resolving them costs nothing.
   * A district's name comes from an upstream list and an organisation's from the
   * Forest Client API, and neither is worth a call per row: a district code is
   * short and familiar, and an organisation number is what people search by.
   */
  public static ScopeDto of(CssRoleNaming.Scope scope) {
    return new ScopeDto(scope.type(), scope.value(), labelFor(scope));
  }

  private static String labelFor(CssRoleNaming.Scope scope) {
    if (!CssRoleNaming.SCOPE_REGION.equalsIgnoreCase(scope.type())) {
      return null;
    }
    // Null for a region retired from the enum: the code is already carried in
    // `value` and reads perfectly well on its own.
    return ca.bc.gov.nrs.fam.constants.Region.fromRegionCode(scope.value())
        .map(ca.bc.gov.nrs.fam.constants.Region::getRegionName)
        .orElse(null);
  }
}
