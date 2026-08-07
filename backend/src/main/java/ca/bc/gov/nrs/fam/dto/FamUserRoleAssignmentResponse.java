package ca.bc.gov.nrs.fam.dto;

import java.util.List;

/** All per-user outcomes for one grant request. */
import io.swagger.v3.oas.annotations.media.Schema;

public record FamUserRoleAssignmentResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<FamUserRoleAssignmentCreateResponse> assignmentsDetail) {}
