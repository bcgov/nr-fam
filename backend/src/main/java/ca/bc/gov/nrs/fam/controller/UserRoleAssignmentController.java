package ca.bc.gov.nrs.fam.controller;

import ca.bc.gov.nrs.fam.constants.ErrorCode;
import ca.bc.gov.nrs.fam.constants.RoleType;
import ca.bc.gov.nrs.fam.dto.FamUserRoleAssignmentCreateRequest;
import ca.bc.gov.nrs.fam.dto.FamUserRoleAssignmentCreateResponse;
import ca.bc.gov.nrs.fam.dto.FamUserRoleAssignmentResponse;
import ca.bc.gov.nrs.fam.dto.TargetUser;
import ca.bc.gov.nrs.fam.dto.TargetUserValidationResult;
import ca.bc.gov.nrs.fam.entity.FamRole;
import ca.bc.gov.nrs.fam.entity.FamUserRoleXref;
import ca.bc.gov.nrs.fam.exception.FamHttpException;
import ca.bc.gov.nrs.fam.security.AuthorizationService;
import ca.bc.gov.nrs.fam.security.Requester;
import ca.bc.gov.nrs.fam.service.AccessGrantedEmailService;
import ca.bc.gov.nrs.fam.service.ApplicationService;
import ca.bc.gov.nrs.fam.service.RoleService;
import ca.bc.gov.nrs.fam.service.TargetUserValidationService;
import ca.bc.gov.nrs.fam.service.UserRoleAssignmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Port of {@code router_user_role_assignment.py}.
 *
 * <p>The guard order matters and matches upstream's {@code Depends} chain: the
 * role must exist before it can be authorised against, target users must be
 * resolved before self-grant and user-type checks can run, and IDIM verification
 * happens before any of it reaches the service layer.
 */
@Slf4j
@RestController
@RequestMapping("/user-role-assignment")
@Tag(name = "FAM User Role Assignment")
@RequiredArgsConstructor
public class UserRoleAssignmentController {

  private final UserRoleAssignmentService userRoleAssignmentService;
  private final TargetUserValidationService targetUserValidationService;
  private final AccessGrantedEmailService accessGrantedEmailService;
  private final AuthorizationService authorizationService;
  private final ApplicationService applicationService;
  private final RoleService roleService;

  @PostMapping
  @Operation(operationId = "create_user_role_assignment_many", summary = "Grant multiple users access to an application's role",
      description = "Granting IDIR/BCeID users access to an application's role, "
          + "supporting expiry dates for role assignments.")
  public FamUserRoleAssignmentResponse createUserRoleAssignmentMany(
      @Valid @RequestBody FamUserRoleAssignmentCreateRequest request, Requester requester) {

    FamRole role = requireRole(request.roleId());
    List<TargetUser> targetUsers = toTargetUsers(request);

    authorizationService.enforceSelfGrant(requester, targetUsers, role);
    authorizationService.enforceBceidTermsConditions(requester);
    authorizationService.authorizeByApplicationRole(role, requester);
    authorizeByPrivilege(request, role, requester);
    authorizationService.authorizeByUserType(requester, targetUsers);

    TargetUserValidationResult validation =
        targetUserValidationService.validateTargetUsers(requester, targetUsers, role);

    List<FamUserRoleAssignmentCreateResponse> granted =
        userRoleAssignmentService.createUserRoleAssignmentMany(
            request, validation.verifiedUsers(), requester);

    List<FamUserRoleAssignmentCreateResponse> results = new ArrayList<>(granted);

    // Users IDIM could not confirm never reach the service, so their failures are
    // appended here to keep the response one entry per requested user.
    validation.failedUsers().forEach(failed ->
        results.add(FamUserRoleAssignmentCreateResponse.failure(
            HttpStatus.BAD_REQUEST.value(),
            failed.errorReason() == null
                ? "User identification validation failed"
                : failed.errorReason())));

    if (request.requiresSendUserEmail()) {
      // After the grant, and never fatal - see AccessGrantedEmailService.
      return new FamUserRoleAssignmentResponse(
          accessGrantedEmailService.sendAccessGrantedEmails(
              validation.verifiedUsers(), results));
    }

    return new FamUserRoleAssignmentResponse(results);
  }

  @DeleteMapping("/{userRoleXrefId}")
  @Operation(operationId = "delete_user_role_assignment", summary = "Remove a specific application's role from a user's access")
  public ResponseEntity<Void> deleteUserRoleAssignment(
      @PathVariable Long userRoleXrefId, Requester requester) {

    FamUserRoleXref userRole = userRoleAssignmentService.findById(userRoleXrefId)
        .orElseThrow(() -> FamHttpException.badRequest(ErrorCode.INVALID_REQUEST_PARAMETER,
            "Parameter 'user_role_xref_id' is missing or invalid."));

    FamRole role = userRole.getRole();
    List<TargetUser> targetUsers = List.of(TargetUser.builder()
        .userId(userRole.getUser().getUserId())
        .userName(userRole.getUser().getUserName())
        .userGuid(userRole.getUser().getUserGuid())
        .userTypeCode(userRole.getUser().getUserTypeCode())
        .businessGuid(userRole.getUser().getBusinessGuid())
        .build());

    authorizationService.enforceSelfGrant(requester, targetUsers, role);
    authorizationService.enforceBceidTermsConditions(requester);
    authorizationService.authorizeByApplicationRole(role, requester);
    authorizationService.authorizeByPrivilege(role, requester);
    authorizationService.authorizeByUserType(requester, targetUsers);
    enforceBceidSameOrg(requester, targetUsers, role);

    userRoleAssignmentService.deleteUserRoleAssignment(requester, userRoleXrefId);
    return ResponseEntity.noContent().build();
  }

  private FamRole requireRole(Long roleId) {
    FamRole role = roleId == null ? null : roleService.getRole(roleId);
    if (role == null) {
      throw FamHttpException.forbidden(ErrorCode.INVALID_ROLE_ID,
          "Role does not exist or failed to get the role_id from request.");
    }
    return role;
  }

  private static List<TargetUser> toTargetUsers(FamUserRoleAssignmentCreateRequest request) {
    return request.users().stream()
        .map(user -> TargetUser.builder()
            .userName(user.userName())
            .userGuid(user.userGuid())
            .userTypeCode(request.userTypeCode().getCode())
            .build())
        .toList();
  }

  /**
   * A delegated admin must hold privilege over the exact role being granted.
   *
   * <p>Port of {@code router_guards.authorize_by_privilege}. For an abstract role
   * granted with forest client numbers, privilege is checked against each
   * client-scoped <em>child</em> role, not the abstract parent: a delegated admin
   * is given authority over specific clients. A child role that does not exist
   * means no such authority was ever granted.
   */
  private void authorizeByPrivilege(
      FamUserRoleAssignmentCreateRequest request, FamRole role, Requester requester) {

    if (applicationService.isAppAdmin(
        role.getApplication().getApplicationId(), requester)) {
      return;
    }

    boolean abstractWithClients = RoleType.ABSTRACT.getCode().equals(role.getRoleTypeCode())
        && request.forestClientNumbers() != null
        && !request.forestClientNumbers().isEmpty();

    if (abstractWithClients) {
      for (String forestClientNumber : request.forestClientNumbers()) {
        String childRoleName = RoleService.constructForestClientRoleName(
            role.getRoleName(), forestClientNumber);

        FamRole childRole = roleService.findByNameAndApplication(
                childRoleName, role.getApplication().getApplicationId())
            .orElseThrow(() -> FamHttpException.forbidden(ErrorCode.PERMISSION_REQUIRED,
                "Requester has no privilege to grant this access."));

        authorizationService.requirePrivilegeOnRole(requester, childRole.getRoleId());
      }
      return;
    }

    authorizationService.requirePrivilegeOnRole(requester, role.getRoleId());
  }

  /**
   * A BCeID requester revoking access must be in the same organisation as the
   * target.
   *
   * <p>Port of {@code router_guards.enforce_bceid_by_same_org_guard}. The target
   * is re-verified against IDIM first, because FAM's stored
   * {@code business_guid} may be stale or absent for an older record.
   */
  private void enforceBceidSameOrg(
      Requester requester, List<TargetUser> targetUsers, FamRole role) {

    if (!requester.isBceid()) {
      return;
    }

    TargetUserValidationResult validation =
        targetUserValidationService.validateTargetUsers(requester, targetUsers, role);

    if (!validation.failedUsers().isEmpty()) {
      String applicationName = role.getApplication().getApplicationName();
      List<String> failedNames = validation.failedUsers().stream()
          .map(u -> u.userName()).toList();
      throw FamHttpException.internalError(ErrorCode.UNKNOWN_STATE,
          "Unable to verify the following users: " + failedNames
              + ". Please contact " + applicationName + " administrator for the action.");
    }

    try {
      targetUserValidationService.validateBceidSameOrg(requester, validation.verifiedUsers());
    } catch (IllegalArgumentException e) {
      throw FamHttpException.forbidden(ErrorCode.DIFFERENT_ORG_GRANT_PROHIBITED,
          "An error occurred while validating organization consistency: " + e.getMessage());
    }
  }
}
