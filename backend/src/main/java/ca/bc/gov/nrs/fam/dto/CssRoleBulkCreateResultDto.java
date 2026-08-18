package ca.bc.gov.nrs.fam.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * The result of defining one role across every environment of an integration.
 *
 * <p>All or nothing: this is only returned when every environment succeeded, so
 * {@code environments} is the complete list of where the role now exists rather
 * than a report of partial success. A clash in any environment, or an upstream
 * failure partway, comes back as an error instead.
 *
 * <p>The environments are those the integration declares, which need not be
 * three - so the screen reports what was actually written rather than assuming
 * dev, test and prod.
 */
public record CssRoleBulkCreateResultDto(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String roleCode,

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String description,

    /** Every environment the role was created in. */
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> environments,

    /** The role as the picker will see it, identical in each environment. */
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) CssRoleOptionDto role) {}
