package ca.bc.gov.nrs.fam.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.List;

/**
 * A CSS integration, as returned by {@code GET /integrations}.
 *
 * <p>Field names mirror the CSS API response, which is camelCase - this is an
 * upstream wire format, not part of FAM's own snake_case API.
 *
 * <p>{@code environments} is an array: one integration spans dev/test/prod. That
 * is why an integration does not map one-to-one onto what FAM calls an
 * application, which has always been per-environment.
 *
 * <p>The naming strategy is pinned because FAM's own snake_case strategy is
 * global and applies to the shared ObjectMapper. Without it, Jackson looks for
 * {@code project_name} in a body carrying {@code projectName} and every
 * camelCase field deserialises to null - which surfaced as "null (DEV)" in the
 * application picker.
 */
@JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public record CssIntegrationDto(
    Integer id,
    String projectName,
    String authType,
    List<String> environments,
    String status,
    String createdAt,
    String updatedAt) {

  /** Never null, so callers can stream over it without a guard. */
  public List<String> environments() {
    return environments == null ? List.of() : environments;
  }
}
