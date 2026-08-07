package ca.bc.gov.nrs.fam.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

/** An application-admin assignment. */

public record FamAppAdminGetResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Long applicationAdminId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Long userId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Long applicationId,
    OffsetDateTime createDate,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) FamUserInfoDto user,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) FamApplicationBase application) {}
