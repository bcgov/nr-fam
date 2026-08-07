package ca.bc.gov.nrs.fam.dto;

import ca.bc.gov.nrs.fam.constants.UserType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Request to make one user a delegated administrator of a role.
 *
 * <p>For a concrete role, {@code forestClientNumbers} is omitted. For an abstract
 * role it is required, and one privilege is created per client - FAM materialises
 * a child role for each.
 */

public record FamAccessControlPrivilegeCreateRequest(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @NotBlank @Size(min = 3, max = 20) String userName,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @NotBlank @Size(min = 32, max = 32) String userGuid,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @NotNull UserType userTypeCode,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @NotNull Long roleId,
    List<@Size(min = 1, max = 8) String> forestClientNumbers,
    boolean requiresSendUserEmail) {}
