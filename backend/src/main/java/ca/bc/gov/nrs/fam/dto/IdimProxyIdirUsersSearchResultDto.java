package ca.bc.gov.nrs.fam.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.List;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * IDIR user search results.
 *
 * <p>The IDIM web service has no notion of a page number - an over-broad search
 * simply returns the first {@code pageSize} matches - so this is not a
 * {@link PagedResults}.
 */
@JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
public record IdimProxyIdirUsersSearchResultDto(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int totalItems,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int pageSize,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<IdimProxyIdirUserSearchItemDto> items) {}
