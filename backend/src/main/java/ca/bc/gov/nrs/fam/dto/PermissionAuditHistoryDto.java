package ca.bc.gov.nrs.fam.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import ca.bc.gov.nrs.fam.constants.PrivilegeChangeType;
import java.time.OffsetDateTime;

/**
 * One entry in a user's permission history for an application.
 *
 * <p>Ordered by {@code changeDate}, which for backfilled rows differs from
 * {@code createDate}.
 */

public record PermissionAuditHistoryDto(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Long privilegeChangeAuditId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) OffsetDateTime createDate,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String createUser,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) OffsetDateTime changeDate,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) PrivilegeChangePerformerDto changePerformerUserDetails,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Long changePerformerUserId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) PrivilegeChangeType privilegeChangeTypeCode,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String privilegeChangeTypeDescription,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) PrivilegeDetailsDto privilegeDetails) {}
