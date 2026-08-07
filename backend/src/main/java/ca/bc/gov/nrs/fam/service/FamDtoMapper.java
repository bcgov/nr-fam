package ca.bc.gov.nrs.fam.service;

import ca.bc.gov.nrs.fam.constants.AppEnv;
import ca.bc.gov.nrs.fam.constants.UserType;
import ca.bc.gov.nrs.fam.dto.FamAccessControlPrivilegeGetResponse;
import ca.bc.gov.nrs.fam.dto.FamAppAdminGetResponse;
import ca.bc.gov.nrs.fam.dto.FamApplicationBase;
import ca.bc.gov.nrs.fam.dto.FamApplicationDto;
import ca.bc.gov.nrs.fam.dto.FamApplicationUserRoleAssignmentGetDto;
import ca.bc.gov.nrs.fam.dto.FamForestClientDto;
import ca.bc.gov.nrs.fam.dto.FamRoleMinDto;
import ca.bc.gov.nrs.fam.dto.FamRoleWithClientDto;
import ca.bc.gov.nrs.fam.dto.FamUserInfoDto;
import ca.bc.gov.nrs.fam.dto.FamUserTypeDto;
import ca.bc.gov.nrs.fam.entity.FamAccessControlPrivilege;
import ca.bc.gov.nrs.fam.entity.FamApplication;
import ca.bc.gov.nrs.fam.entity.FamApplicationAdmin;
import ca.bc.gov.nrs.fam.entity.FamForestClient;
import ca.bc.gov.nrs.fam.entity.FamRole;
import ca.bc.gov.nrs.fam.entity.FamUser;
import ca.bc.gov.nrs.fam.entity.FamUserRoleXref;
import org.springframework.stereotype.Component;

/**
 * Entity to DTO conversion.
 *
 * <p>Written by hand rather than generated, because several fields are renamed on
 * the way out ({@code role_purpose} to {@code description},
 * {@code forest_client_relation} to {@code forest_client}, {@code user_type_code}
 * to {@code code}) and others are deliberately withheld.
 */
@Component
public class FamDtoMapper {

  public FamApplicationDto toApplicationDto(FamApplication application) {
    if (application == null) {
      return null;
    }
    return new FamApplicationDto(
        application.getApplicationId(),
        application.getApplicationName(),
        application.getApplicationDescription());
  }

  public FamUserTypeDto toUserTypeDto(FamUser user) {
    if (user == null || user.getUserTypeCode() == null) {
      return null;
    }
    String description = user.getUserType() != null ? user.getUserType().getDescription() : null;
    return new FamUserTypeDto(
        UserType.fromCode(user.getUserTypeCode()).orElse(null), description);
  }

  /** Omits user_guid, business_guid, the OIDC subject and the audit columns. */
  public FamUserInfoDto toUserInfoDto(FamUser user) {
    if (user == null) {
      return null;
    }
    return new FamUserInfoDto(
        user.getUserName(),
        toUserTypeDto(user),
        user.getFirstName(),
        user.getLastName(),
        user.getEmail());
  }

  /**
   * FAM stores only the client number; {@code clientName} and {@code status} are
   * left null here and filled in later from the Forest Client API.
   */
  public FamForestClientDto toForestClientDto(FamForestClient forestClient) {
    if (forestClient == null) {
      return null;
    }
    return new FamForestClientDto(null, forestClient.getForestClientNumber(), null);
  }

  public FamRoleMinDto toRoleMinDto(FamRole role) {
    if (role == null) {
      return null;
    }
    return new FamRoleMinDto(
        role.getRoleName(), role.getRoleTypeCode(), toApplicationDto(role.getApplication()));
  }

  public FamRoleWithClientDto toRoleWithClientDto(FamRole role) {
    if (role == null) {
      return null;
    }
    return new FamRoleWithClientDto(
        role.getRoleId(),
        role.getRoleName(),
        role.getRoleTypeCode(),
        role.getDisplayName(),
        role.getRolePurpose(),
        toApplicationDto(role.getApplication()),
        toForestClientDto(role.getForestClient()),
        toRoleMinDto(role.getParentRole()));
  }

  /** Application view used by the admin surface, which also shows the environment. */
  public FamApplicationBase toApplicationBase(FamApplication application) {
    if (application == null) {
      return null;
    }
    return new FamApplicationBase(
        application.getApplicationId(),
        application.getApplicationName(),
        application.getApplicationDescription(),
        AppEnv.fromCode(application.getAppEnvironment()).orElse(null));
  }

  public FamAppAdminGetResponse toAppAdminResponse(FamApplicationAdmin admin) {
    if (admin == null) {
      return null;
    }
    return new FamAppAdminGetResponse(
        admin.getApplicationAdminId(),
        admin.getUser() != null ? admin.getUser().getUserId() : null,
        admin.getApplication() != null ? admin.getApplication().getApplicationId() : null,
        admin.getCreateDate(),
        toUserInfoDto(admin.getUser()),
        toApplicationBase(admin.getApplication()));
  }

  public FamAccessControlPrivilegeGetResponse toAccessControlPrivilegeResponse(
      FamAccessControlPrivilege privilege) {
    if (privilege == null) {
      return null;
    }
    return new FamAccessControlPrivilegeGetResponse(
        privilege.getAccessControlPrivilegeId(),
        privilege.getUser() != null ? privilege.getUser().getUserId() : null,
        privilege.getRole() != null ? privilege.getRole().getRoleId() : null,
        toUserInfoDto(privilege.getUser()),
        toRoleWithClientDto(privilege.getRole()),
        privilege.getCreateDate());
  }

  public FamApplicationUserRoleAssignmentGetDto toAssignmentDto(FamUserRoleXref xref) {
    if (xref == null) {
      return null;
    }
    return new FamApplicationUserRoleAssignmentGetDto(
        xref.getUserRoleXrefId(),
        xref.getUser() != null ? xref.getUser().getUserId() : null,
        xref.getRole() != null ? xref.getRole().getRoleId() : null,
        toUserInfoDto(xref.getUser()),
        toRoleWithClientDto(xref.getRole()),
        xref.getCreateDate(),
        xref.getExpiryDate());
  }
}
