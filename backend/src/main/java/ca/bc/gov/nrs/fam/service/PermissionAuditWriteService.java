package ca.bc.gov.nrs.fam.service;

import ca.bc.gov.nrs.fam.constants.ApiInstanceEnv;
import ca.bc.gov.nrs.fam.constants.ErrorCode;
import ca.bc.gov.nrs.fam.constants.PrivilegeChangeType;
import ca.bc.gov.nrs.fam.constants.PrivilegeDetailsPermissionType;
import ca.bc.gov.nrs.fam.constants.PrivilegeDetailsScopeType;
import ca.bc.gov.nrs.fam.dto.FamUserRoleAssignmentCreateResponse;
import ca.bc.gov.nrs.fam.dto.PrivilegeChangePerformerDto;
import ca.bc.gov.nrs.fam.dto.PrivilegeDetailsDto;
import ca.bc.gov.nrs.fam.dto.PrivilegeDetailsRoleDto;
import ca.bc.gov.nrs.fam.dto.PrivilegeDetailsScopeDto;
import ca.bc.gov.nrs.fam.entity.FamPrivilegeChangeAudit;
import ca.bc.gov.nrs.fam.entity.FamPrivilegeChangeType;
import ca.bc.gov.nrs.fam.entity.FamRole;
import ca.bc.gov.nrs.fam.entity.FamUser;
import ca.bc.gov.nrs.fam.entity.FamUserRoleXref;
import ca.bc.gov.nrs.fam.exception.FamHttpException;
import ca.bc.gov.nrs.fam.integration.ForestClientIntegrationService;
import ca.bc.gov.nrs.fam.repository.FamPrivilegeChangeAuditRepository;
import ca.bc.gov.nrs.fam.security.Requester;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the privilege change audit trail.
 *
 * <p>Port of {@code crud/services/permission_audit_service.py}. Records are
 * append-only and capture a <em>snapshot</em> of the performer and the privilege,
 * so the trail stays truthful after users or roles are later changed or removed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PermissionAuditWriteService {

  private final FamPrivilegeChangeAuditRepository auditRepository;
  private final ForestClientIntegrationService forestClientIntegrationService;
  private final ApiInstanceEnvResolver apiInstanceEnvResolver;
  private final EntityManager entityManager;
  private final ObjectMapper objectMapper;

  /**
   * Record a grant.
   *
   * <p>One record per target user covering all the roles granted to them in that
   * request. Nothing is written when no grant succeeded.
   */
  @Transactional
  public void storeGranted(
      Requester requester,
      FamUser changeTargetUser,
      List<FamUserRoleAssignmentCreateResponse> assignments) {

    List<FamUserRoleAssignmentCreateResponse> successes =
        assignments.stream().filter(FamUserRoleAssignmentCreateResponse::isSuccess).toList();

    if (successes.isEmpty()) {
      log.debug("No successful grants; no audit record to store.");
      return;
    }

    Long applicationId = successes.get(0).detail().role().application().applicationId();

    save(requester, changeTargetUser, applicationId, PrivilegeChangeType.GRANT,
        toGrantedDetails(successes));
  }

  /** Record a revocation, capturing the privilege as it was immediately before deletion. */
  @Transactional
  public void storeRevoked(Requester requester, FamUserRoleXref deletedRecord) {
    FamRole role = deletedRecord.getRole();

    save(requester, deletedRecord.getUser(), role.getApplication().getApplicationId(),
        PrivilegeChangeType.REVOKE, toRevokedDetails(deletedRecord));
  }

  /**
   * Persist one audit row. Package-visible so
   * {@link AdminPermissionAuditWriteService} writes administrator changes through
   * the same path rather than duplicating it.
   */
  @Transactional
  void save(
      Requester requester, FamUser changeTargetUser, Long applicationId,
      PrivilegeChangeType changeType, PrivilegeDetailsDto privilegeDetails) {

    FamPrivilegeChangeAudit audit = new FamPrivilegeChangeAudit();
    audit.setApplication(entityManager.getReference(
        ca.bc.gov.nrs.fam.entity.FamApplication.class, applicationId));
    audit.setChangeTargetUser(changeTargetUser);
    audit.setChangePerformerUser(
        entityManager.getReference(FamUser.class, requester.userId()));
    audit.setPrivilegeChangeType(
        entityManager.getReference(FamPrivilegeChangeType.class, changeType.name()));
    audit.setCreateUser(requester.userName());
    // change_date is set explicitly rather than defaulted: for backfilled rows it
    // is not the same as create_date, so it is never derived from one.
    audit.setChangeDate(OffsetDateTime.now());
    audit.setChangePerformerUserDetails(toJson(performerDetails(requester)));
    audit.setPrivilegeDetails(toJson(privilegeDetails));

    log.debug("Adding audit record for {}", changeType);
    auditRepository.save(audit);
  }

  private static PrivilegeChangePerformerDto performerDetails(Requester requester) {
    return new PrivilegeChangePerformerDto(
        requester.userName(), requester.firstName(), requester.lastName(), requester.email());
  }

  /**
   * Build the granted-privilege document.
   *
   * <p>FAM currently supports one role per request, optionally scoped to several
   * forest clients under a single CLIENT scope type. When the role is scoped, the
   * expiry lives on each scope; when it is not, it lives on the role.
   */
  private PrivilegeDetailsDto toGrantedDetails(
      List<FamUserRoleAssignmentCreateResponse> successes) {

    var firstRole = successes.get(0).detail().role();
    boolean scoped = firstRole.forestClient() != null;

    PrivilegeDetailsRoleDto roleDetails;
    if (scoped) {
      List<PrivilegeDetailsScopeDto> scopes = successes.stream()
          .map(item -> new PrivilegeDetailsScopeDto(
              PrivilegeDetailsScopeType.CLIENT,
              item.detail().role().forestClient().forestClientNumber(),
              item.detail().role().forestClient().clientName(),
              item.detail().expiryDate() == null
                  ? null
                  : item.detail().expiryDate().toString()))
          .toList();
      roleDetails = new PrivilegeDetailsRoleDto(firstRole.displayName(), scopes, null);
    } else {
      String expiry = successes.get(0).detail().expiryDate() == null
          ? null
          : successes.get(0).detail().expiryDate().toString();
      roleDetails = new PrivilegeDetailsRoleDto(firstRole.displayName(), null, expiry);
    }

    return new PrivilegeDetailsDto(
        PrivilegeDetailsPermissionType.END_USER, List.of(roleDetails));
  }

  /**
   * Build the revoked-privilege document.
   *
   * <p>For a client-scoped role the client name is fetched from the Forest Client
   * API so the audit entry is readable later. Unlike the read-path enrichment this
   * does <em>not</em> soft-fail: an audit record that cannot name the client it
   * describes is not worth writing, so the revocation is rejected instead.
   */
  private PrivilegeDetailsDto toRevokedDetails(FamUserRoleXref deletedRecord) {
    FamRole role = deletedRecord.getRole();
    String expiry = deletedRecord.getExpiryDate() == null
        ? null
        : deletedRecord.getExpiryDate().toString();

    PrivilegeDetailsRoleDto roleDetails;
    if (role.getForestClient() != null) {
      String forestClientNumber = role.getForestClient().getForestClientNumber();
      ApiInstanceEnv apiInstanceEnv = apiInstanceEnvResolver.resolve(role.getApplication());

      List<Map<String, Object>> results = forestClientIntegrationService.search(
          List.of(forestClientNumber), apiInstanceEnv, false);

      if (!ForestClientValidator.numberExists(results)) {
        String message = "Revoke user permission encountered problem."
            + "Unknown forest client number " + forestClientNumber + " for "
            + "scoped permission " + role.getRoleName() + ".";
        log.debug(message);
        throw FamHttpException.internalError(ErrorCode.UNKNOWN_STATE, message);
      }

      roleDetails = new PrivilegeDetailsRoleDto(
          role.getDisplayName(),
          List.of(new PrivilegeDetailsScopeDto(
              PrivilegeDetailsScopeType.CLIENT,
              forestClientNumber,
              ForestClientValidator.clientName(results),
              expiry)),
          null);
    } else {
      roleDetails = new PrivilegeDetailsRoleDto(role.getDisplayName(), null, expiry);
    }

    return new PrivilegeDetailsDto(
        PrivilegeDetailsPermissionType.END_USER, List.of(roleDetails));
  }

  private String toJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw FamHttpException.internalError(
          ErrorCode.UNKNOWN_STATE, "Cannot serialise audit detail.");
    }
  }
}
