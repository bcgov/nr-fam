package ca.bc.gov.nrs.fam.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * The outcome of renaming a role in every environment.
 *
 * <p>Both lists, so the screen can say what actually changed: an environment that
 * already had the new text was left alone rather than rewritten.
 */
public record CssRoleBulkUpdateResultDto(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String roleCode,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String roleName,

    /** Environments whose name or description was changed. */
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> updatedEnvironments,

    /** Environments that already matched, and were not touched. */
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> unchangedEnvironments) {}
