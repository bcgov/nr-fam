package ca.bc.gov.nrs.fam.dto;

import ca.bc.gov.nrs.fam.constants.UserType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Request to make one user an administrator of one application. */

public record FamAppAdminCreateRequest(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @NotBlank @Size(min = 3, max = 20) String userName,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @NotBlank @Size(min = 32, max = 32) String userGuid,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @NotNull UserType userTypeCode,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @NotNull Long applicationId) {}
