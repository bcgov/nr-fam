package ca.bc.gov.nrs.fam.service;

import ca.bc.gov.nrs.fam.constants.PrivilegeChangeType;
import ca.bc.gov.nrs.fam.constants.PrivilegeDetailsPermissionType;
import ca.bc.gov.nrs.fam.constants.PrivilegeDetailsScopeType;
import ca.bc.gov.nrs.fam.dto.FamAccessControlPrivilegeCreateResponse;
import ca.bc.gov.nrs.fam.dto.PrivilegeDetailsDto;
import ca.bc.gov.nrs.fam.dto.PrivilegeDetailsRoleDto;
import ca.bc.gov.nrs.fam.dto.PrivilegeDetailsScopeDto;
import ca.bc.gov.nrs.fam.entity.FamAccessControlPrivilege;
import ca.bc.gov.nrs.fam.entity.FamApplicationAdmin;
import ca.bc.gov.nrs.fam.entity.FamRole;
import ca.bc.gov.nrs.fam.entity.FamUser;
import ca.bc.gov.nrs.fam.security.Requester;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Audit writes for administrator permission changes.
 *
 * <p>Port of {@code admin_management/.../permission_audit_service.py}. It writes
 * to the same {@code fam_privilege_change_audit} table as the end-user path and
 * shares {@link PermissionAuditWriteService}'s persistence, differing only in the
 * {@code permission_type} recorded and how the privilege detail is shaped:
 *
 * <ul>
 *   <li>{@code APPLICATION_ADMIN} - no roles at all; the authority is over the
 *       whole application.
 *   <li>{@code DELEGATED_ADMIN} - the role the admin may now grant, with the
 *       forest clients it is scoped to.
 * </ul>
 *
 * <p>Delegated-admin scopes carry no expiry: a delegated administrator's
 * <em>authority</em> does not expire, only the end-user assignments they create.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminPermissionAuditWriteService {

  private final PermissionAuditWriteService auditWriteService;

  @Transactional
  public void storeApplicationAdminGranted(
      Requester requester, FamUser changeTargetUser, FamApplicationAdmin admin) {

    auditWriteService.save(requester, changeTargetUser,
        admin.getApplication().getApplicationId(), PrivilegeChangeType.GRANT,
        applicationAdminDetails());
  }

  @Transactional
  public void storeApplicationAdminRevoked(Requester requester, FamApplicationAdmin admin) {
    auditWriteService.save(requester, admin.getUser(),
        admin.getApplication().getApplicationId(), PrivilegeChangeType.REVOKE,
        applicationAdminDetails());
  }

  @Transactional
  public void storeDelegatedAdminGranted(
      Requester requester,
      FamUser changeTargetUser,
      List<FamAccessControlPrivilegeCreateResponse> granted) {

    List<FamAccessControlPrivilegeCreateResponse> successes =
        granted.stream().filter(FamAccessControlPrivilegeCreateResponse::isSuccess).toList();

    if (successes.isEmpty()) {
      log.debug("No successful delegated admin grants; no audit record to store.");
      return;
    }

    Long applicationId =
        successes.get(0).detail().role().application().applicationId();

    auditWriteService.save(requester, changeTargetUser, applicationId,
        PrivilegeChangeType.GRANT, delegatedAdminGrantedDetails(successes));
  }

  @Transactional
  public void storeDelegatedAdminRevoked(
      Requester requester, FamAccessControlPrivilege deletedRecord) {

    FamRole role = deletedRecord.getRole();

    auditWriteService.save(requester, deletedRecord.getUser(),
        role.getApplication().getApplicationId(), PrivilegeChangeType.REVOKE,
        delegatedAdminRevokedDetails(role));
  }

  /** Application-admin authority has no role granularity, so roles stay absent. */
  private static PrivilegeDetailsDto applicationAdminDetails() {
    return new PrivilegeDetailsDto(PrivilegeDetailsPermissionType.APPLICATION_ADMIN, null);
  }

  private static PrivilegeDetailsDto delegatedAdminGrantedDetails(
      List<FamAccessControlPrivilegeCreateResponse> successes) {

    var firstRole = successes.get(0).detail().role();
    boolean scoped = firstRole.forestClient() != null;

    List<PrivilegeDetailsScopeDto> scopes = scoped
        ? successes.stream()
            .map(item -> new PrivilegeDetailsScopeDto(
                PrivilegeDetailsScopeType.CLIENT,
                item.detail().role().forestClient().forestClientNumber(),
                item.detail().role().forestClient().clientName(),
                // A delegated admin's authority does not expire.
                null))
            .toList()
        : null;

    return new PrivilegeDetailsDto(
        PrivilegeDetailsPermissionType.DELEGATED_ADMIN,
        List.of(new PrivilegeDetailsRoleDto(firstRole.displayName(), scopes, null)));
  }

  private static PrivilegeDetailsDto delegatedAdminRevokedDetails(FamRole role) {
    List<PrivilegeDetailsScopeDto> scopes = role.getForestClient() == null
        ? null
        : List.of(new PrivilegeDetailsScopeDto(
            PrivilegeDetailsScopeType.CLIENT,
            role.getForestClient().getForestClientNumber(),
            // FAM does not store the client name; the audit records the number.
            null,
            null));

    return new PrivilegeDetailsDto(
        PrivilegeDetailsPermissionType.DELEGATED_ADMIN,
        List.of(new PrivilegeDetailsRoleDto(role.getDisplayName(), scopes, null)));
  }
}
