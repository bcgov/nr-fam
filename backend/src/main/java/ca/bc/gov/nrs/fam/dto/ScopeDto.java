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

  public static ScopeDto of(CssRoleNaming.Scope scope) {
    return new ScopeDto(scope.type(), scope.value(), null);
  }
}
