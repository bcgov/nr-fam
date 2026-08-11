package ca.bc.gov.nrs.fam.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** Pagination metadata returned alongside a page of results. */
public record PageResultMeta(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Total records counts for query conditions") long total,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Total pages for query records") int numberOfPages,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Page number") int pageNumber,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Number of records per page") int pageSize) {}
