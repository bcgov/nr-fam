package ca.bc.gov.nrs.fam.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

/** One row of the application's user-role listing. */

public record FamApplicationUserRoleAssignmentGetDto(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Long userRoleXrefId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Long userId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Long roleId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) FamUserInfoDto user,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) FamRoleWithClientDto role,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) OffsetDateTime createDate,
    OffsetDateTime expiryDate) {}
