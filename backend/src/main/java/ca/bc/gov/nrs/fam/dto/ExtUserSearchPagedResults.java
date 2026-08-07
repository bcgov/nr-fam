package ca.bc.gov.nrs.fam.dto;

import java.util.List;

/**
 * External user-search envelope.
 *
 * <p>The results key is {@code users}, not {@code results} as on the internal
 * paged endpoints.
 */
public record ExtUserSearchPagedResults(
    ExtPageResultMeta meta, List<ExtApplicationUserSearchGetDto> users) {}
