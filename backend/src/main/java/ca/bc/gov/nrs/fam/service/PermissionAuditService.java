package ca.bc.gov.nrs.fam.service;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import ca.bc.gov.nrs.fam.constants.ErrorCode;
import ca.bc.gov.nrs.fam.constants.PrivilegeChangeType;
import ca.bc.gov.nrs.fam.constants.UserType;
import ca.bc.gov.nrs.fam.dto.PermissionAuditHistoryDto;
import ca.bc.gov.nrs.fam.security.AuditUser;
import ca.bc.gov.nrs.fam.dto.PrivilegeChangePerformerDto;
import ca.bc.gov.nrs.fam.dto.CssRoleNaming;
import ca.bc.gov.nrs.fam.dto.PrivilegeDetailsDto;
import ca.bc.gov.nrs.fam.dto.PrivilegeDetailsRoleDto;
import ca.bc.gov.nrs.fam.integration.CssApiService;
import ca.bc.gov.nrs.fam.entity.FamPrivilegeChangeAudit;
import ca.bc.gov.nrs.fam.exception.FamHttpException;
import ca.bc.gov.nrs.fam.repository.FamPrivilegeChangeAuditRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read access to the privilege change audit trail.
 *
 * <p>Port of {@code crud_permission_audit.py}. The two JSONB columns are stored
 * as raw JSON on the entity and deserialised here, keeping Hibernate out of the
 * business of understanding their shape.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PermissionAuditService {

  private final FamPrivilegeChangeAuditRepository auditRepository;
  private final ObjectMapper objectMapper;
  private final CssApiService cssApiService;

  /**
   * Most recent change first.
   *
   * <p>Takes the target as a GUID plus its directory rather than the stored
   * {@code <TYPE>\<GUID>} string. Composing it here keeps the encoding in one
   * place - {@link AuditUser} - and keeps the query an equality match the
   * history index can serve, which a suffix match on the GUID alone could not.
   */
  @Transactional(readOnly = true)
  public List<PermissionAuditHistoryDto> getHistory(
      String targetUserGuid, UserType targetUserType,
      Integer cssIntegrationId, String cssEnvironment) {
    String targetUser = AuditUser.of(targetUserType, targetUserGuid);

    /*
      One lookup for the whole page rather than one per row. The trail records a
      role's code, which is the part that has to stay true; what people
      recognise is its name, and that lives in a sidecar in CSS.
    */
    Map<String, String> displayNames = roleDisplayNames(cssIntegrationId, cssEnvironment);

    return auditRepository.findHistory(targetUser, cssIntegrationId, cssEnvironment).stream()
        .map(audit -> toDto(audit, displayNames))
        .toList();
  }

  /**
   * Role code to the name people know it by, for one application.
   *
   * <p>Best effort. CSS being unreachable must not turn a history request into a
   * failure - the trail is complete without it, and every code reads perfectly
   * well on its own. An empty map simply means every pill shows its code.
   */
  private Map<String, String> roleDisplayNames(Integer integrationId, String environment) {
    if (integrationId == null || environment == null) {
      return Map.of();
    }
    try {
      Map<String, String> names = new HashMap<>();
      cssApiService.getRoles(integrationId, environment).forEach(role ->
          CssRoleNaming.parseLabel(role.name())
              .ifPresent(label -> names.put(label.roleCode(), label.text())));
      return names;
    } catch (Exception e) {
      log.warn("Could not read role names for integration {} ({}); "
          + "the history will show role codes.", integrationId, environment, e);
      return Map.of();
    }
  }

  /** Fills in the name beside each code, where one is known. */
  private PrivilegeDetailsDto withDisplayNames(
      PrivilegeDetailsDto details, Map<String, String> displayNames) {

    if (details == null || details.roles() == null) {
      return details;
    }
    return new PrivilegeDetailsDto(
        details.permissionType(),
        details.roles().stream()
            .map(role -> new PrivilegeDetailsRoleDto(
                role.role(),
                role.scopes(),
                role.roleAssignmentExpiryDate(),
                displayNames.get(role.role())))
            .toList());
  }

  private PermissionAuditHistoryDto toDto(
      FamPrivilegeChangeAudit audit, Map<String, String> displayNames) {
    return new PermissionAuditHistoryDto(
        audit.getPrivilegeChangeAuditId(),
        audit.getCreateDate(),
        audit.getCreateUser(),
        audit.getChangeDate(),
        readJson(audit.getChangePerformerUserDetails(), PrivilegeChangePerformerDto.class,
            audit.getPrivilegeChangeAuditId(), "change_performer_user_details"),
        // The performer is recorded as a GUID now; there is no user id to return.
        null,
        PrivilegeChangeType.valueOf(
            audit.getPrivilegeChangeType().getPrivilegeChangeTypeCode()),
        audit.getPrivilegeChangeType().getDescription(),
        withDisplayNames(
            readJson(audit.getPrivilegeDetails(), PrivilegeDetailsDto.class,
                audit.getPrivilegeChangeAuditId(), "privilege_details"),
            displayNames));
  }

  /**
   * Audit rows are append-only and written by FAM itself, so unreadable JSON means
   * corrupted data rather than bad input - surfaced as a 500 rather than silently
   * returning a partial record.
   */
  private <T> T readJson(String json, Class<T> type, UUID auditId, String column) {
    if (json == null) {
      return null;
    }
    try {
      return objectMapper.readValue(json, type);
    } catch (JsonProcessingException e) {
      log.error("Cannot parse {} of audit record {}", column, auditId, e);
      throw FamHttpException.internalError(ErrorCode.UNKNOWN_STATE,
          "Audit record " + auditId + " has unreadable " + column + ".");
    }
  }
}
