package ca.bc.gov.nrs.fam.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * One user named in a grant request.
 *
 * <p>Both fields are required: the GUID is the identity that is verified against
 * IDIM and stored, while the user name is what the requester typed and is
 * cross-checked against the GUID's real name.
 */
public record FamUserRoleAssignmentUserDto(
    @NotBlank @Size(min = 3, max = 20) String userName,
    @NotBlank @Size(min = 32, max = 32) String userGuid) {}
