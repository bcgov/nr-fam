package ca.bc.gov.nrs.fam.service;

import ca.bc.gov.nrs.fam.constants.ApiInstanceEnv;
import ca.bc.gov.nrs.fam.constants.EmailSendingStatus;
import ca.bc.gov.nrs.fam.constants.ErrorCode;
import ca.bc.gov.nrs.fam.constants.RoleType;
import ca.bc.gov.nrs.fam.dto.FamAccessControlPrivilegeCreateRequest;
import ca.bc.gov.nrs.fam.dto.FamAccessControlPrivilegeCreateResponse;
import ca.bc.gov.nrs.fam.dto.FamAccessControlPrivilegeGetResponse;
import ca.bc.gov.nrs.fam.dto.FamForestClientDto;
import ca.bc.gov.nrs.fam.dto.FamRoleWithClientDto;
import ca.bc.gov.nrs.fam.dto.PagedResults;
import ca.bc.gov.nrs.fam.dto.UserRolePageParams;
import ca.bc.gov.nrs.fam.dto.TargetUser;
import ca.bc.gov.nrs.fam.entity.FamAccessControlPrivilege;
import ca.bc.gov.nrs.fam.entity.FamRole;
import ca.bc.gov.nrs.fam.entity.FamUser;
import ca.bc.gov.nrs.fam.exception.FamHttpException;
import ca.bc.gov.nrs.fam.integration.ForestClientIntegrationService;
import ca.bc.gov.nrs.fam.repository.DelegatedAdminSpecs;
import ca.bc.gov.nrs.fam.repository.FamAccessControlPrivilegeRepository;
import ca.bc.gov.nrs.fam.security.Requester;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Granting and revoking delegated-administrator privileges.
 *
 * <p>Port of {@code admin_management/.../access_control_privilege_service.py}.
 *
 * <p><strong>Note the difference from end-user grants.</strong> That path is
 * partial-success: an inactive forest client fails one entry and the rest
 * proceed. Here an invalid or inactive client <em>aborts the whole request</em>.
 * That is upstream's behaviour and it is kept deliberately - granting
 * administrative authority over some clients but not others, without saying so,
 * would be worse than refusing outright. The per-item response shape remains
 * because a request can still produce a mix of created and already-existing
 * privileges.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccessControlPrivilegeService {

  private final FamAccessControlPrivilegeRepository accessControlPrivilegeRepository;
  private final UserService userService;
  private final RoleService roleService;
  private final ForestClientIntegrationService forestClientIntegrationService;
  private final ApiInstanceEnvResolver apiInstanceEnvResolver;
  private final AdminPermissionAuditWriteService permissionAuditWriteService;
  private final AccessGrantedEmailService accessGrantedEmailService;
  private final FamDtoMapper mapper;

  /** Unpaged listing, used by the CSV export. */
  @Transactional(readOnly = true)
  public List<FamAccessControlPrivilegeGetResponse> getByApplicationId(Long applicationId) {
    return accessControlPrivilegeRepository.findByApplicationId(applicationId).stream()
        .map(mapper::toAccessControlPrivilegeResponse)
        .toList();
  }

  /**
   * Paged listing for the delegated-admin table.
   *
   * <p>Upstream paged this endpoint; the table sends page, size, search and sort
   * on every request, so returning a plain list would break its pagination.
   */
  @Transactional(readOnly = true)
  public PagedResults<FamAccessControlPrivilegeGetResponse> getPagedByApplicationId(
      Long applicationId, UserRolePageParams pageParams) {

    Page<FamAccessControlPrivilege> page = accessControlPrivilegeRepository.findAll(
        DelegatedAdminSpecs.forApplication(applicationId, pageParams),
        PageRequest.of(pageParams.toZeroBasedPage(), pageParams.getPageSize()));

    return PagedResults.from(page,
        page.getContent().stream().map(mapper::toAccessControlPrivilegeResponse).toList());
  }

  @Transactional(readOnly = true)
  public FamAccessControlPrivilege getById(Long accessControlPrivilegeId) {
    return accessControlPrivilegeRepository.findById(accessControlPrivilegeId)
        .orElseThrow(() -> FamHttpException.badRequest(ErrorCode.INVALID_REQUEST_PARAMETER,
            "Access control privilege id " + accessControlPrivilegeId + " does not exist."));
  }

  /**
   * Make a user a delegated administrator of a role.
   *
   * <p>A concrete role yields one privilege. An abstract role yields one per
   * forest client, each against the materialised child role.
   */
  @Transactional
  public List<FamAccessControlPrivilegeCreateResponse> createMany(
      FamAccessControlPrivilegeCreateRequest request,
      Requester requester,
      TargetUser targetUser) {

    log.debug("Assigning delegated admin privilege for role {}", request.roleId());

    FamUser famUser = userService.findOrCreate(
        request.userTypeCode().getCode(), request.userName(), request.userGuid(),
        requester.oidcUserId());
    famUser = userService.updateFromVerifiedTargetUser(
        famUser.getUserId(), targetUser, requester.oidcUserId());

    FamRole famRole = roleService.getRole(request.roleId());
    if (famRole == null) {
      throw FamHttpException.badRequest(ErrorCode.INVALID_REQUEST_PARAMETER,
          "Role id " + request.roleId() + " does not exist.");
    }

    List<FamAccessControlPrivilegeCreateResponse> results =
        RoleType.ABSTRACT.getCode().equals(famRole.getRoleTypeCode())
            ? grantScoped(request, famRole, famUser, requester)
            : List.of(grantPrivilege(famUser, famRole, requester));

    permissionAuditWriteService.storeDelegatedAdminGranted(requester, famUser, results);

    log.debug("Delegated admin privilege assignment produced {} outcome(s)", results.size());
    return results;
  }

  private List<FamAccessControlPrivilegeCreateResponse> grantScoped(
      FamAccessControlPrivilegeCreateRequest request,
      FamRole parentRole,
      FamUser famUser,
      Requester requester) {

    if (request.forestClientNumbers() == null || request.forestClientNumbers().isEmpty()) {
      throw FamHttpException.badRequest(ErrorCode.MISSING_KEY_ATTRIBUTE,
          "Invalid access control privilege request, missing forest client number.");
    }

    ApiInstanceEnv apiInstanceEnv =
        apiInstanceEnvResolver.resolve(parentRole.getApplication());

    List<FamAccessControlPrivilegeCreateResponse> results = new ArrayList<>();

    for (String forestClientNumber : request.forestClientNumbers()) {
      List<Map<String, Object>> searchResult = forestClientIntegrationService.search(
          List.of(forestClientNumber), apiInstanceEnv, false);

      // Aborts the request rather than failing one entry - see the class comment.
      if (!ForestClientValidator.numberExists(searchResult)) {
        throw FamHttpException.badRequest(ErrorCode.INVALID_REQUEST_PARAMETER,
            "Invalid access control privilege request. Forest client number "
                + forestClientNumber + " does not exist.");
      }
      if (!ForestClientValidator.isActive(searchResult)) {
        throw FamHttpException.badRequest(ErrorCode.INVALID_REQUEST_PARAMETER,
            "Invalid access control privilege request. Forest client number "
                + forestClientNumber + " is not in active status: "
                + ForestClientValidator.status(searchResult) + ".");
      }

      FamRole childRole = roleService.findOrCreateForestClientChildRole(
          forestClientNumber, parentRole, requester.oidcUserId());

      FamAccessControlPrivilegeCreateResponse response =
          grantPrivilege(famUser, childRole, requester);

      // FAM stores only the client number, so the name from this search is
      // attached for the UI and the audit record.
      results.add(withForestClientName(response, searchResult));
    }

    return results;
  }

  /**
   * Create the privilege, or report the existing one.
   *
   * <p>An existing privilege is a 409 carrying it, not an error - the UI shows it
   * as "already a delegated admin for this role".
   */
  private FamAccessControlPrivilegeCreateResponse grantPrivilege(
      FamUser user, FamRole role, Requester requester) {

    Optional<FamAccessControlPrivilege> existing = accessControlPrivilegeRepository
        .findByUserUserIdAndRoleRoleId(user.getUserId(), role.getRoleId());

    if (existing.isPresent()) {
      FamAccessControlPrivilege privilege = existing.get();
      return new FamAccessControlPrivilegeCreateResponse(
          HttpStatus.CONFLICT.value(),
          mapper.toAccessControlPrivilegeResponse(privilege),
          "User already has the requested access control privilege for "
              + privilege.getRole().getRoleName());
    }

    FamAccessControlPrivilege privilege = new FamAccessControlPrivilege();
    privilege.setUser(user);
    privilege.setRole(role);
    privilege.setCreateUser(requester.oidcUserId());

    FamAccessControlPrivilege saved = accessControlPrivilegeRepository.save(privilege);

    return new FamAccessControlPrivilegeCreateResponse(
        HttpStatus.OK.value(), mapper.toAccessControlPrivilegeResponse(saved), null);
  }

  private FamAccessControlPrivilegeCreateResponse withForestClientName(
      FamAccessControlPrivilegeCreateResponse response, List<Map<String, Object>> searchResult) {

    if (response.detail() == null || response.detail().role() == null) {
      return response;
    }

    FamForestClientDto forestClient =
        ForestClientEnrichmentService.toForestClientDto(searchResult.get(0));

    FamRoleWithClientDto role = response.detail().role();
    FamRoleWithClientDto namedRole = new FamRoleWithClientDto(
        role.roleId(), role.roleName(), role.roleTypeCode(), role.displayName(),
        role.description(), role.application(), forestClient, role.parentRole());

    FamAccessControlPrivilegeGetResponse detail = response.detail();
    return new FamAccessControlPrivilegeCreateResponse(
        response.statusCode(),
        new FamAccessControlPrivilegeGetResponse(
            detail.accessControlPrivilegeId(), detail.userId(), detail.roleId(),
            detail.user(), namedRole, detail.createDate()),
        response.errorMessage());
  }

  /** Revoke a delegated-admin privilege. The audit record is written before the delete. */
  @Transactional
  public void delete(Requester requester, Long accessControlPrivilegeId) {
    FamAccessControlPrivilege privilege = getById(accessControlPrivilegeId);

    permissionAuditWriteService.storeDelegatedAdminRevoked(requester, privilege);

    accessControlPrivilegeRepository.delete(privilege);
    log.debug("Delegated admin privilege {} deleted", accessControlPrivilegeId);
  }

  /**
   * Notify the new delegated admin.
   *
   * <p>Best-effort, as with end-user grants: the privilege is already committed,
   * so a notification failure is reported rather than raised.
   */
  public EmailSendingStatus sendEmailNotification(
      TargetUser targetUser, List<FamAccessControlPrivilegeCreateResponse> assignments) {

    return accessGrantedEmailService.sendDelegatedAdminGrantedEmail(targetUser, assignments);
  }
}
