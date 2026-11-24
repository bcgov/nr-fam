package ca.bc.gov.nrs.fam.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * A role's new name and description.
 *
 * <p>No role code: the code is what people hold and what applications authorise
 * on, so it is not editable. Only the text FAM shows beside it changes. The
 * limits match {@link CssRoleCreateRequest}, so a role can always be given back
 * the text it was created with.
 */
public record CssRoleUpdateRequest(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "View All")
    @NotBlank @Size(max = 150) String roleName,

    /** Optional. Blank removes the description. */
    @Schema(example = "Allows users to view all the FSPs but not edit")
    @Size(max = 180) String description) {}
