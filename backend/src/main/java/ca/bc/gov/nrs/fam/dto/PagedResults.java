package ca.bc.gov.nrs.fam.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.springframework.data.domain.Page;

/**
 * Envelope for a page of results.
 *
 * <p>Deliberately not Spring Data's own {@code Page} serialisation: the frontend
 * consumes the {@code meta}/{@code results} shape that upstream's
 * {@code PagedResultsSchema} produced.
 */
public record PagedResults<T>(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) PageResultMeta meta,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Paged results")
    List<T> results) {

  public static <E, T> PagedResults<T> from(Page<E> page, List<T> results) {
    return new PagedResults<>(
        new PageResultMeta(
            page.getTotalElements(),
            page.getTotalPages(),
            page.getNumber() + 1, // Spring pages are 0-based; the API is 1-based.
            page.getSize()),
        results);
  }
}
