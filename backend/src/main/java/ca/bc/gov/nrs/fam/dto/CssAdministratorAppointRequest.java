package ca.bc.gov.nrs.fam.dto;

import ca.bc.gov.nrs.fam.constants.UserType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Appoint or remove an application administrator.
 *
 * <p>Names a person and nothing else. Unlike {@link CssDelegatedAdminRequest}
 * there is no role and no scope, because an application administrator is
 * authorised over the application as a whole - that is the distinction between
 * the two tiers, and it shows up here as a smaller request.
 */
public record CssAdministratorAppointRequest(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @NotBlank String userGuid,

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @NotNull UserType userType) {}
