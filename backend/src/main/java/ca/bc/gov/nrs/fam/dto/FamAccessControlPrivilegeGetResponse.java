package ca.bc.gov.nrs.fam.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

/** A delegated-admin privilege. */

public record FamAccessControlPrivilegeGetResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Long accessControlPrivilegeId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Long userId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Long roleId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) FamUserInfoDto user,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) FamRoleWithClientDto role,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) OffsetDateTime createDate) {}
