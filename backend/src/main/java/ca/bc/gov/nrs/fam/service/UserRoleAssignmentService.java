package ca.bc.gov.nrs.fam.service;

import ca.bc.gov.nrs.fam.constants.ApiInstanceEnv;
import ca.bc.gov.nrs.fam.constants.ErrorCode;
import ca.bc.gov.nrs.fam.constants.RoleType;
import ca.bc.gov.nrs.fam.constants.UserType;
import ca.bc.gov.nrs.fam.dto.FamApplicationUserRoleAssignmentGetDto;
import ca.bc.gov.nrs.fam.dto.FamForestClientDto;
import ca.bc.gov.nrs.fam.dto.FamRoleWithClientDto;
import ca.bc.gov.nrs.fam.dto.FamUserRoleAssignmentCreateRequest;
import ca.bc.gov.nrs.fam.dto.FamUserRoleAssignmentCreateResponse;
import ca.bc.gov.nrs.fam.dto.TargetUser;
import ca.bc.gov.nrs.fam.entity.FamRole;
import ca.bc.gov.nrs.fam.entity.FamUser;
import ca.bc.gov.nrs.fam.entity.FamUserRoleXref;
import ca.bc.gov.nrs.fam.exception.FamHttpException;
import ca.bc.gov.nrs.fam.integration.ForestClientIntegrationService;
import ca.bc.gov.nrs.fam.repository.FamUserRoleXrefRepository;
import ca.bc.gov.nrs.fam.security.Requester;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Granting and revoking end-user access to application roles.
 *
 * <p>Port of {@code crud_user_role.py}.
 *
 * <p>Granting is deliberately <strong>partial-success</strong>. A batch may name
 * fifty users across several forest clients, and any one of them can fail
 * independently - IDIM cannot verify them, they belong to another organisation,
 * a client number is inactive, the role is already assigned. Each combination
 * produces its own outcome and the rest of the batch proceeds. An exception here
 * would discard work that legitimately succeeded.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserRoleAssignmentService {

  private final FamUserRoleXrefRepository userRoleXrefRepository;
  private final UserService userService;
  private final RoleService roleService;
  private final ForestClientIntegrationService forestClientIntegrationService;
  private final ApiInstanceEnvResolver apiInstanceEnvResolver;
  private final TargetUserValidationService targetUserValidationService;
  private final PermissionAuditWriteService permissionAuditWriteService;
  private final ExpiryDateParser expiryDateParser;
  private final FamDtoMapper mapper;

  /**
   * Grant one role to a batch of already-verified users.
   *
   * @param verifiedUsers users that passed IDIM verification. Users that failed
   *     are reported by the caller, which holds the failure reasons.
   */
  @Transactional
  public List<FamUserRoleAssignmentCreateResponse> createUserRoleAssignmentMany(
      FamUserRoleAssignmentCreateRequest request,
      List<TargetUser> verifiedUsers,
      Requester requester) {

    // Only IDIR and Business BCeID may be granted roles; the enum also carries
    // the BCSC codes, which are not valid here.
    UserType userType = request.userTypeCode();
    if (userType != UserType.IDIR && userType != UserType.BCEID) {
      throw FamHttpException.badRequest(ErrorCode.INVALID_REQUEST_PARAMETER,
          "Invalid user type: " + userType.getCode() + ".");
    }

    FamRole famRole = roleService.getRole(request.roleId());
    if (famRole == null) {
      throw FamHttpException.badRequest(ErrorCode.INVALID_REQUEST_PARAMETER,
          "Role id " + request.roleId() + " does not exist.");
    }

    OffsetDateTime expiryDate = expiryDateParser.parse(request.expiryDateDate());
    List<FamUserRoleAssignmentCreateResponse> results = new ArrayList<>();

    // A BCeID requester may only grant to their own organisation. Rejected users
    // become failures in the response rather than aborting the batch.
    var split = targetUserValidationService.splitBySameOrg(
        requester, verifiedUsers, request.userTypeCode().getCode());

    split.failedUsers().forEach(failed -> {
      log.debug("BCeID same-organization validation failed for {}: {}",
          failed.userName(), failed.errorReason());
      results.add(FamUserRoleAssignmentCreateResponse.failure(
          HttpStatus.FORBIDDEN.value(), failed.errorReason()));
    });

    boolean requiresChildRole = RoleType.ABSTRACT.getCode().equals(famRole.getRoleTypeCode());

    // Audit records are written per user, covering every role granted to them.
    Map<Long, UserAssignments> assignmentsByUser = new LinkedHashMap<>();

    for (TargetUser targetUser : split.validUsers()) {
      try {
        FamUser famUser = userService.findOrCreate(
            request.userTypeCode().getCode(), targetUser.userName(), targetUser.userGuid(),
            requester.oidcUserId());
        famUser = userService.updateFromVerifiedTargetUser(
            famUser.getUserId(), targetUser, requester.oidcUserId());

        if (requiresChildRole) {
          grantScopedRole(request, famRole, famUser, requester, expiryDate, results,
              assignmentsByUser);
        } else {
          FamUserRoleAssignmentCreateResponse response =
              createAssignment(famUser, famRole, requester, expiryDate);
          results.add(response);
          record(assignmentsByUser, famUser, response);
        }
      } catch (Exception e) {
        log.error("Grant failed for user {}", targetUser.userName(), e);
        results.add(FamUserRoleAssignmentCreateResponse.failure(
            HttpStatus.INTERNAL_SERVER_ERROR.value(), e.getMessage()));
      }
    }

    assignmentsByUser.values().forEach(entry ->
        permissionAuditWriteService.storeGranted(requester, entry.user(), entry.assignments()));

    log.info("User/role assignment executed with {} outcome(s)", results.size());
    return results;
  }

  /**
   * Grant an abstract role, materialising one concrete child role per forest
   * client.
   *
   * <p>Each client number is validated against the Forest Client API first: it
   * must exist and be active. A client that fails produces its own failure entry
   * and the remaining clients still proceed.
   */
  private void grantScopedRole(
      FamUserRoleAssignmentCreateRequest request,
      FamRole parentRole,
      FamUser famUser,
      Requester requester,
      OffsetDateTime expiryDate,
      List<FamUserRoleAssignmentCreateResponse> results,
      Map<Long, UserAssignments> assignmentsByUser) {

    if (request.forestClientNumbers() == null || request.forestClientNumbers().isEmpty()) {
      results.add(FamUserRoleAssignmentCreateResponse.failure(HttpStatus.BAD_REQUEST.value(),
          "Invalid user role assignment request, missing forest client number."));
      return;
    }

    ApiInstanceEnv apiInstanceEnv =
        apiInstanceEnvResolver.resolve(parentRole.getApplication());

    for (String forestClientNumber : request.forestClientNumbers()) {
      try {
        List<Map<String, Object>> searchResult = forestClientIntegrationService.search(
            List.of(forestClientNumber), apiInstanceEnv, false);

        if (!ForestClientValidator.numberExists(searchResult)) {
          results.add(FamUserRoleAssignmentCreateResponse.failure(
              HttpStatus.BAD_REQUEST.value(),
              "Invalid role assignment request. Forest Client Number "
                  + forestClientNumber + " does not exist."));
          continue;
        }

        if (!ForestClientValidator.isActive(searchResult)) {
          results.add(FamUserRoleAssignmentCreateResponse.failure(
              HttpStatus.BAD_REQUEST.value(),
              "Invalid role assignment request. Forest client number " + forestClientNumber
                  + " is not in active status:" + ForestClientValidator.status(searchResult)));
          continue;
        }

        FamRole childRole = roleService.findOrCreateForestClientChildRole(
            forestClientNumber, parentRole, requester.oidcUserId());

        FamUserRoleAssignmentCreateResponse response =
            createAssignment(famUser, childRole, requester, expiryDate);

        // FAM stores only the client number, so the name from this search is
        // attached to the response for the UI and the audit record.
        response = withForestClientName(response, searchResult);

        results.add(response);
        record(assignmentsByUser, famUser, response);

      } catch (Exception e) {
        log.error("Grant failed for client {}", forestClientNumber, e);
        results.add(FamUserRoleAssignmentCreateResponse.failure(
            HttpStatus.INTERNAL_SERVER_ERROR.value(), e.getMessage()));
      }
    }
  }

  /**
   * Create the assignment, or report the existing one.
   *
   * <p>An already-assigned role is a 409 carrying the existing assignment, not an
   * error - the frontend shows it as "already has this access".
   */
  private FamUserRoleAssignmentCreateResponse createAssignment(
      FamUser user, FamRole role, Requester requester, OffsetDateTime expiryDate) {

    Optional<FamUserRoleXref> existing =
        userRoleXrefRepository.findByUserUserIdAndRoleRoleId(user.getUserId(), role.getRoleId());

    if (existing.isPresent()) {
      FamUserRoleXref xref = existing.get();
      return new FamUserRoleAssignmentCreateResponse(
          HttpStatus.CONFLICT.value(),
          mapper.toAssignmentDto(xref),
          "Role " + xref.getRole().getRoleName() + " already assigned to user "
              + xref.getUser().getUserName() + ".",
          ca.bc.gov.nrs.fam.constants.EmailSendingStatus.NOT_REQUIRED);
    }

    FamUserRoleXref xref = new FamUserRoleXref();
    xref.setUser(user);
    xref.setRole(role);
    xref.setExpiryDate(expiryDate);
    xref.setCreateUser(requester.oidcUserId());

    FamUserRoleXref saved = userRoleXrefRepository.save(xref);
    log.debug("Assigned role {} to user {}", role.getRoleName(), user.getUserName());

    return FamUserRoleAssignmentCreateResponse.success(mapper.toAssignmentDto(saved));
  }

  private FamUserRoleAssignmentCreateResponse withForestClientName(
      FamUserRoleAssignmentCreateResponse response, List<Map<String, Object>> searchResult) {

    if (response.detail() == null || response.detail().role() == null) {
      return response;
    }

    FamForestClientDto forestClient =
        ForestClientEnrichmentService.toForestClientDto(searchResult.get(0));

    FamRoleWithClientDto role = response.detail().role();
    FamRoleWithClientDto namedRole = new FamRoleWithClientDto(
        role.roleId(), role.roleName(), role.roleTypeCode(), role.displayName(),
        role.description(), role.application(), forestClient, role.parentRole());

    FamApplicationUserRoleAssignmentGetDto detail = response.detail();
    return response.withDetail(new FamApplicationUserRoleAssignmentGetDto(
        detail.userRoleXrefId(), detail.userId(), detail.roleId(), detail.user(), namedRole,
        detail.createDate(), detail.expiryDate()));
  }

  /**
   * Revoke an assignment.
   *
   * <p>The audit record is written <em>before</em> the delete, while the role and
   * user are still readable.
   */
  @Transactional
  public void deleteUserRoleAssignment(Requester requester, Long userRoleXrefId) {
    FamUserRoleXref record = userRoleXrefRepository.findById(userRoleXrefId)
        .orElseThrow(() -> FamHttpException.badRequest(ErrorCode.INVALID_REQUEST_PARAMETER,
            "Parameter 'user_role_xref_id' is missing or invalid."));

    permissionAuditWriteService.storeRevoked(requester, record);

    userRoleXrefRepository.delete(record);
    log.debug("Revoked user role assignment {}", userRoleXrefId);
  }

  @Transactional(readOnly = true)
  public Optional<FamUserRoleXref> findById(Long userRoleXrefId) {
    return userRoleXrefRepository.findById(userRoleXrefId);
  }

  private static void record(
      Map<Long, UserAssignments> assignmentsByUser, FamUser user,
      FamUserRoleAssignmentCreateResponse response) {
    assignmentsByUser
        .computeIfAbsent(user.getUserId(), k -> new UserAssignments(user, new ArrayList<>()))
        .assignments()
        .add(response);
  }

  private record UserAssignments(
      FamUser user, List<FamUserRoleAssignmentCreateResponse> assignments) {}
}
