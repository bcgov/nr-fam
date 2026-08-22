package ca.bc.gov.nrs.fam.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import java.util.List;

/**
 * The values chosen for one scope dimension of a grant.
 *
 * <p>A role scoped by district <em>and</em> forest client is granted against a
 * pair, so a request carries one of these per dimension and the service takes
 * the cross-product: three districts and two clients authorise six pairs, and
 * each pair is its own CSS role.
 *
 * <p>Kept general rather than fixed fields for district and client. The scope
 * types are a closed set today, but the naming, ordering and cross-product code
 * is all written against a list, and one more place hard-coding the pair is one
 * more place to change.
 */
public record CssScopeSelection(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "DISTRICT")
    @NotBlank String type,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    List<String> values) {

  public List<String> values() {
    return values == null ? List.of() : values;
  }
}
