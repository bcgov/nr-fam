package ca.bc.gov.nrs.fam.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** A page of IDIR search results from nr-user-lookup-api. */
@JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public record UserLookupIdirSearchResult(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int totalItems,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int pageSize,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<UserLookupIdirUserDto> items) {

  /** Never null, so callers can iterate without a guard. */
  public List<UserLookupIdirUserDto> items() {
    return items == null ? List.of() : items;
  }

  public static UserLookupIdirSearchResult empty() {
    return new UserLookupIdirSearchResult(0, 0, List.of());
  }
}
