package ca.bc.gov.nrs.fam.service;

import ca.bc.gov.nrs.fam.constants.ErrorCode;
import ca.bc.gov.nrs.fam.constants.PrivilegeChangeType;
import ca.bc.gov.nrs.fam.constants.PrivilegeDetailsPermissionType;
import ca.bc.gov.nrs.fam.constants.PrivilegeDetailsScopeType;
import ca.bc.gov.nrs.fam.dto.CssUserRoleAssignmentResult;
import ca.bc.gov.nrs.fam.dto.PrivilegeChangePerformerDto;
import ca.bc.gov.nrs.fam.dto.PrivilegeDetailsDto;
import ca.bc.gov.nrs.fam.dto.PrivilegeDetailsRoleDto;
import ca.bc.gov.nrs.fam.dto.PrivilegeDetailsScopeDto;
import ca.bc.gov.nrs.fam.dto.RoleDefinitionDetailsDto;
import ca.bc.gov.nrs.fam.entity.FamPrivilegeChangeAudit;
import ca.bc.gov.nrs.fam.entity.FamPrivilegeChangeType;
import ca.bc.gov.nrs.fam.exception.FamHttpException;
import ca.bc.gov.nrs.fam.repository.FamPrivilegeChangeAuditRepository;
import ca.bc.gov.nrs.fam.security.Requester;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the privilege change audit trail.
 *
 * <p>Roles and assignments live in CSS, but the audit stays in FAM's own tables -
 * CSS keeps no history of who granted what to whom, so if this is not recorded
 * here it is not recorded anywhere.
 *
 * <p>Records are append-only and capture a <em>snapshot</em> of the performer and
 * the privilege, so the trail stays truthful after roles are later renamed or
 * users removed. Since V94 nothing here is a foreign key.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PermissionAuditWriteService {

  private final FamPrivilegeChangeAuditRepository auditRepository;
  private final EntityManager entityManager;
  private final ObjectMapper objectMapper;

  /**
   * Record a grant made through CSS.
   *
   * <p>One record per target user covering every role assigned to them in that
   * request. Only roles that were actually assigned are recorded: a partly
   * successful grant must not claim the failures, or the trail overstates what
   * the user was given.
   *
   * <p>Nothing is written when no assignment succeeded.
   */
  @Transactional
  public void storeCssGranted(
      Requester requester,
      String targetUserGuid,
      String targetUserTypeCode,
      int cssIntegrationId,
      String cssEnvironment,
      String roleName,
      String scopeType,
      List<CssUserRoleAssignmentResult> results) {

    List<CssUserRoleAssignmentResult> assigned =
        results.stream().filter(CssUserRoleAssignmentResult::assigned).toList();

    if (assigned.isEmpty()) {
      log.debug("No successful CSS assignments; no audit record to store.");
      return;
    }

    save(requester, targetUserGuid, targetUserTypeCode, cssIntegrationId, cssEnvironment,
        PrivilegeChangeType.GRANT,
        toCssDetails(roleName, scopeType, assigned));
  }

  /**
   * Record a revocation made through CSS.
   *
   * <p>Takes the role names as they were immediately before removal, since after
   * the fact there is nothing left in CSS to describe.
   */
  @Transactional
  public void storeCssRevoked(
      Requester requester,
      String targetUserGuid,
      String targetUserTypeCode,
      int cssIntegrationId,
      String cssEnvironment,
      String roleName,
      String scopeType,
      List<String> revokedRoleNames) {

    if (revokedRoleNames.isEmpty()) {
      log.debug("No roles revoked; no audit record to store.");
      return;
    }

    List<CssUserRoleAssignmentResult> asResults = revokedRoleNames.stream()
        .map(name -> new CssUserRoleAssignmentResult(name, false, true, null,
            ca.bc.gov.nrs.fam.constants.EmailSendingStatus.NOT_REQUIRED))
        .toList();

    save(requester, targetUserGuid, targetUserTypeCode, cssIntegrationId, cssEnvironment,
        PrivilegeChangeType.REVOKE,
        toCssDetails(roleName, scopeType, asResults));
  }

  /**
   * Record the definition of a role.
   *
   * <p>Wider than the other two: a grant changes what one person can do, this
   * changes what the application's roles mean. CSS keeps no history of either, so
   * without this row nothing anywhere records who introduced a role.
   *
   * <p><b>No target user</b>, which is what makes this row shaped differently -
   * there is nobody it was done to. Those columns are left null rather than
   * pointed at the performer, which would read as somebody granting themselves
   * something.
   *
   * <p>Not swallowed on failure. If this write fails the role already exists in
   * CSS and the caller is told the operation failed, which is the same bargain the
   * grant path makes: an unrecorded change is worse than a confusing one, and the
   * code is left free for a retry that does get recorded.
   *
   * @param scopeType {@code DISTRICT}, {@code FOREST_CLIENT} or null
   */
  @Transactional
  public void storeRoleCreated(
      Requester requester,
      int cssIntegrationId,
      String cssEnvironment,
      String roleCode,
      String description,
      String scopeType) {

    save(requester, null, null, cssIntegrationId, cssEnvironment,
        PrivilegeChangeType.CREATE_ROLE,
        RoleDefinitionDetailsDto.of(
            roleCode, description, toRequiredScopeType(scopeType)));
  }

  /**
   * Null for an unscoped role, rather than the CLIENT default the grant path
   * uses: there, a role is always scoped by something and CLIENT is the older of
   * the two. Here "no scope required" is a real answer and must not read as
   * "scoped by forest client".
   */
  private static PrivilegeDetailsScopeType toRequiredScopeType(String scopeType) {
    if (scopeType == null || scopeType.isBlank()) {
      return null;
    }
    return toDetailScopeType(scopeType);
  }

  /** Persist one audit row. */
  @Transactional
  void save(
      Requester requester,
      String targetUserGuid,
      String targetUserTypeCode,
      Integer cssIntegrationId,
      String cssEnvironment,
      PrivilegeChangeType changeType,
      // Typed as Object because the document's shape follows the change type: a
      // grant records privileges, a role definition records a role.
      Object privilegeDetails) {

    FamPrivilegeChangeAudit audit = new FamPrivilegeChangeAudit();
    audit.setCssIntegrationId(cssIntegrationId);
    audit.setCssEnvironment(cssEnvironment);
    audit.setTargetUserGuid(targetUserGuid);
    audit.setTargetUserTypeCode(targetUserTypeCode);
    audit.setPerformerUserGuid(requester == null ? null : requester.userGuid());
    audit.setPrivilegeChangeType(
        entityManager.getReference(FamPrivilegeChangeType.class, changeType.name()));
    audit.setCreateUser(requester == null ? "system" : requester.userName());
    // change_date is set explicitly rather than defaulted: for backfilled rows it
    // is not the same as create_date, so it is never derived from one.
    audit.setChangeDate(OffsetDateTime.now());
    audit.setChangePerformerUserDetails(toJson(performerDetails(requester)));
    audit.setPrivilegeDetails(toJson(privilegeDetails));

    log.debug("Adding audit record for {} on integration {}/{}",
        changeType, cssIntegrationId, cssEnvironment);
    auditRepository.save(audit);
  }

  private static PrivilegeChangePerformerDto performerDetails(Requester requester) {
    if (requester == null) {
      return new PrivilegeChangePerformerDto("system", null, null, null);
    }
    return new PrivilegeChangePerformerDto(
        requester.userName(), requester.firstName(), requester.lastName(), requester.email());
  }

  /**
   * Build the privilege document for a CSS grant or revocation.
   *
   * <p>The scope value is recovered from the generated role name rather than
   * passed in, because that name is the only place CSS records it - the same
   * reason the read path has to parse it back out.
   *
   * <p>There is no expiry: CSS has no concept of one, so the expiry fields stay
   * null rather than carrying a value the underlying system will not honour.
   */
  private PrivilegeDetailsDto toCssDetails(
      String roleName, String scopeType, List<CssUserRoleAssignmentResult> assigned) {

    PrivilegeDetailsScopeType detailScopeType = toDetailScopeType(scopeType);

    List<PrivilegeDetailsScopeDto> scopes = assigned.stream()
        .map(result -> ca.bc.gov.nrs.fam.dto.CssRoleNaming.parse(result.roleName()))
        .filter(parsed -> parsed.scopeValue() != null)
        .map(parsed -> new PrivilegeDetailsScopeDto(
            detailScopeType, parsed.scopeValue(), null, null))
        .toList();

    PrivilegeDetailsRoleDto roleDetails = scopes.isEmpty()
        ? new PrivilegeDetailsRoleDto(roleName, null, null)
        : new PrivilegeDetailsRoleDto(roleName, scopes, null);

    return new PrivilegeDetailsDto(
        PrivilegeDetailsPermissionType.END_USER, List.of(roleDetails));
  }

  /** Defaults to CLIENT, which is the only scope type FAM had before districts. */
  private static PrivilegeDetailsScopeType toDetailScopeType(String scopeType) {
    return "DISTRICT".equalsIgnoreCase(scopeType)
        ? PrivilegeDetailsScopeType.DISTRICT
        : PrivilegeDetailsScopeType.CLIENT;
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
