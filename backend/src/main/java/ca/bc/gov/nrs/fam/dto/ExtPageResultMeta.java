package ca.bc.gov.nrs.fam.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * Pagination metadata for the external API.
 *
 * <p>Deliberately a different shape from the internal {@link PageResultMeta}:
 * {@code total}/{@code pageCount}/{@code page}/{@code size} rather than
 * {@code total}/{@code number_of_pages}/{@code page_number}/{@code page_size}.
 * Both are published contracts and neither can move to match the other.
 */
@JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
public record ExtPageResultMeta(long total, int pageCount, int page, int size) {}
