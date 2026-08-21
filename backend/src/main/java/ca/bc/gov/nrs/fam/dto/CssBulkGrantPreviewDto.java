package ca.bc.gov.nrs.fam.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * What a bulk upload would do, before it does it.
 *
 * <p>Returned by the preview, which writes nothing. The apply step validates the
 * file again rather than trusting this back: a preview travels through the
 * browser, and granting what the browser hands back would let an edited payload
 * grant something the checks never saw.
 */
public record CssBulkGrantPreviewDto(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<CssBulkGrantRowDto> rows,

    /** Rows that would be granted. */
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int validCount,

    /** Rows that would not, each carrying its own reason. */
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int errorCount) {

  public static CssBulkGrantPreviewDto of(List<CssBulkGrantRowDto> rows) {
    int valid = (int) rows.stream().filter(CssBulkGrantRowDto::valid).count();
    return new CssBulkGrantPreviewDto(rows, valid, rows.size() - valid);
  }
}
