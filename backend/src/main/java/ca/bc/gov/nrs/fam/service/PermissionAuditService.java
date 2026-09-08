package ca.bc.gov.nrs.fam.service;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import ca.bc.gov.nrs.fam.constants.ErrorCode;
import ca.bc.gov.nrs.fam.constants.FamAdminRole;
import ca.bc.gov.nrs.fam.constants.PrivilegeChangeType;
import ca.bc.gov.nrs.fam.constants.UserType;
import ca.bc.gov.nrs.fam.dto.PermissionAuditHistoryDto;
import ca.bc.gov.nrs.fam.dto.PermissionAuditUserDto;
import ca.bc.gov.nrs.fam.security.AuditUser;
import ca.bc.gov.nrs.fam.dto.PrivilegeChangePerformerDto;
import ca.bc.gov.nrs.fam.dto.PrivilegeChangeTargetDto;
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
   * Everyone with audit history in one application, most recently changed first.
   *
   * <p>What the history screen offers once an application is chosen. Read from
   * the trail rather than from CSS, and the difference matters: this is the list
   * of people something has <em>happened to</em> here, so it includes those whose
   * access was since removed - who are exactly the people somebody looking at a
   * history screen is often after - and leaves out anyone granted their access
   * before FAM recorded anything.
   *
   * <p>One row per person, carrying the newest snapshot the trail holds of them.
   * A person renamed or removed since still reads as they did at the time, which
   * is the point of recording it rather than resolving it now.
   */
  @Transactional(readOnly = true)
  public List<PermissionAuditUserDto> getUsersWithHistory(
      Integer cssIntegrationId, String cssEnvironment) {

    List<Object[]> rows =
        auditRepository.findTargetUsersForApplication(cssIntegrationId, cssEnvironment);

    /*
        Newest first out of the query, so the first row seen for a person is
        their most recent - a LinkedHashMap keeps both facts: one entry each, and
        the order the list is shown in.
    */
    Map<String, PermissionAuditUserDto> newestPerUser = new LinkedHashMap<>();

    for (Object[] row : rows) {
      String targetUser = (String) row[0];
      if (targetUser == null || newestPerUser.containsKey(targetUser.toUpperCase(Locale.ROOT))) {
        continue;
      }

      UserType userType = userTypeOf(targetUser);
      if (userType == null) {
        // A row written against 'system' or a key this version cannot read. It
        // names no person, so there is nobody to offer.
        continue;
      }

      PrivilegeChangeTargetDto target = readTargetDetails(row[2]);

      newestPerUser.put(targetUser.toUpperCase(Locale.ROOT), new PermissionAuditUserDto(
          guidOf(targetUser),
          userType,
          target == null ? null : target.username(),
          target == null ? null : target.firstName(),
          target == null ? null : target.lastName(),
          target == null ? null : target.email(),
          row[1] == null ? null : row[1].toString()));
    }

    log.debug("Returning {} user(s) with history in integration {} ({}).",
        newestPerUser.size(), cssIntegrationId, cssEnvironment);
    return List.copyOf(newestPerUser.values());
  }

  /**
   * The directory half of a {@code <TYPE>\<GUID>} key, or null.
   *
   * <p>Null for {@code system} and for anything else that does not carry one:
   * those rows name no person, and guessing a directory would offer a history
   * lookup that matches nothing.
   */
  private static UserType userTypeOf(String targetUser) {
    int separator = targetUser.indexOf(AuditUser.SEPARATOR);
    if (separator <= 0) {
      return null;
    }
    /*
        By code, not by constant name.

        AuditUser writes userType.getCode(), so a Business BCeID row is stored as
        "BCEID_BUS\<guid>" - and valueOf looks for a constant called BCEID_BUS,
        which does not exist. Every BCeID target therefore parsed as null and was
        dropped from the list a few lines up, as though the row named nobody.

        IDIR survived because its code and its constant name are the same string.
        So the user-history list quietly held IDIR users only, and a BCeID
        administrator - who may now only grant to BCeID users - saw an empty one
        and was told nothing had been recorded.
    */
    return UserType.fromCode(targetUser.substring(0, separator).toUpperCase(Locale.ROOT))
        .orElse(null);
  }

  private static String guidOf(String targetUser) {
    int separator = targetUser.indexOf(AuditUser.SEPARATOR);
    return separator <= 0 ? targetUser : targetUser.substring(separator + 1);
  }

  /**
   * The snapshot of who a change was made to, where the row carries one.
   *
   * <p>Null rather than throwing for a row whose JSON will not read: one
   * unreadable snapshot should cost that person their name in the list, not
   * empty the list for everybody.
   */
  private PrivilegeChangeTargetDto readTargetDetails(Object json) {
    if (json == null) {
      return null;
    }
    try {
      return objectMapper.readValue(json.toString(), PrivilegeChangeTargetDto.class);
    } catch (JsonProcessingException e) {
      log.warn("Unreadable change_target_user_details on an audit row: {}", e.getMessage());
      return null;
    }
  }

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

  /**
   * Fills in the name beside each code, where the row does not already carry one.
   *
   * <p><b>The row's own name wins.</b> It was written when the change was made,
   * which is the whole point of recording it: a role's name lives on a sidecar
   * role in CSS and deleting the role takes the sidecar with it, so resolving
   * names on read loses them for exactly the roles most worth reading about. It
   * is also what the role was called <em>then</em>, which is what a trail is for.
   *
   * <p>The live lookup remains as a fallback, for rows written before the name
   * was recorded. Those read exactly as they did before.
   */
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
                nameFor(role, displayNames)))
            .toList());
  }

  /**
   * What a role reads as, in order of what is most true.
   *
   * <p>The row's own name first - written when the change was made, so it
   * survives the role being deleted and says what it was called then. Then the
   * application's sidecars, for rows written before FAM recorded it. Then, for
   * FAM's own administrative roles, what they are: those live on FAM's
   * integration and carry no sidecar, so nothing above can name them and the
   * trail was showing {@code DEVOPS_ADMIN_6538_DEV} where an appointment
   * belonged.
   *
   * <p>The code last, which is the honest answer for a role nothing can name -
   * one added directly in the CSS console, with no sidecar of its own.
   */
  private static String nameFor(
      PrivilegeDetailsRoleDto role, Map<String, String> displayNames) {

    if (role.roleDisplayName() != null && !role.roleDisplayName().isBlank()) {
      return role.roleDisplayName();
    }
    String fromSidecar = displayNames.get(role.role());
    if (fromSidecar != null) {
      return fromSidecar;
    }
    return FamAdminRole.describe(role.role()).orElse(null);
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
