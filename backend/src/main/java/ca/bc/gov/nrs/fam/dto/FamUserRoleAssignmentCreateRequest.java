package ca.bc.gov.nrs.fam.dto;

import ca.bc.gov.nrs.fam.constants.UserType;
import ca.bc.gov.nrs.fam.constants.FamConstants;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Request to grant one role to one or more users.
 *
 * <p>Every user in a request shares the same {@code userTypeCode} and role - the
 * screen grants one role to a batch of users of one identity type.
 *
 * @param forestClientNumbers required when the role is abstract; a concrete child
 *     role is created per client. Ignored for a concrete role.
 * @param expiryDateDate {@code YYYY-MM-DD} in BC time, or null for no expiry. See
 *     {@link ca.bc.gov.nrs.fam.service.ExpiryDateParser} for how it becomes an
 *     instant.
 */
public record FamUserRoleAssignmentCreateRequest(
    @NotEmpty
    @Size(max = FamConstants.MAX_NUM_USERS_ASSIGNMENT_GRANT,
        message = "Can only grant at most 50 users")
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @Valid List<FamUserRoleAssignmentUserDto> users,

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @NotNull UserType userTypeCode,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @NotNull Long roleId,

    List<@Size(min = 1, max = 8) String> forestClientNumbers,

    boolean requiresSendUserEmail,

    @Schema(nullable = true,
        description = "The expiry date as a string (YYYY-MM-DD), BC timezone. "
        + "If provided, the role is valid until the end of this day.")
    String expiryDateDate) {}
