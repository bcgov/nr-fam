package ca.bc.gov.nrs.fam.service;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import ca.bc.gov.nrs.fam.constants.DirectoryEnv;
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
import ca.bc.gov.nrs.fam.integration.UserLookupClient;
import ca.bc.gov.nrs.fam.entity.FamPrivilegeChangeAudit;
import ca.bc.gov.nrs.fam.exception.FamHttpException;
import ca.bc.gov.nrs.fam.repository.FamPrivilegeChangeAuditRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
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

  /**
   * How many unnamed people are worth a directory call before the screen is made
   * to wait. Matches {@code AssignmentRowEnrichmentService}, for the same reason:
   * this runs while somebody looks at a loading table.
   */
  private static final int MAX_NAME_LOOKUPS = 25;

  private final FamPrivilegeChangeAuditRepository auditRepository;
  private final ObjectMapper objectMapper;
  private final CssApiService cssApiService;
  private final UserLookupClient userLookupClient;
  private final ApiInstanceEnvResolver apiInstanceEnvResolver;
  private final PermissionAuditWriteService auditWriteService;

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
    return withResolvedNames(List.copyOf(newestPerUser.values()), cssEnvironment);
  }

  /**
   * Names the people the trail could not name, from the directory.
   *
   * <p><b>Only this list, never the history below it.</b> The rows of a person's
   * history are snapshots - what was recorded at the moment of the change, which
   * is the point of recording them - and resolving those on read would replace
   * what was true then with what is true now. This list answers a different
   * question: who are these people, so somebody can pick one. A name is the
   * right answer to that whenever one can be had.
   *
   * <p>Which rows need it is not arbitrary. FAM's own records carry a snapshot,
   * so they arrive named. Records migrated from the legacy system often do not:
   * legacy stored identity details for the <em>performer</em> of a change and
   * nothing for its target, so anybody who only ever had access granted to them
   * - and never granted any themselves - reached here as a username and a GUID.
   *
   * <p><b>The snapshot always wins.</b> A row that carries a name keeps it, even
   * if the directory would now answer differently. Only the gaps are filled.
   *
   * <p>Best effort, on the same reasoning as
   * {@link AssignmentRowEnrichmentService}: the list is already correct and
   * complete without names, so a directory that is slow or unreachable should
   * cost a few blank cells rather than the screen.
   */
  private List<PermissionAuditUserDto> withResolvedNames(
      List<PermissionAuditUserDto> users, String cssEnvironment) {

    List<PermissionAuditUserDto> unnamed = users.stream()
        .filter(user -> !hasName(user) && user.targetUserGuid() != null)
        .toList();

    if (unnamed.isEmpty()) {
      return users;
    }

    /*
        First, what FAM already knows about these people from anywhere else.

        A person unnamed in this application's trail is very often named in
        another's, because FAM snapshots identity every time it records a change
        - and only legacy rows arrive without one. This costs a single query, it
        is a contemporaneous record rather than a present-day answer, and it
        still works for somebody who has since left, where the directory does
        not. Whatever it cannot answer falls through to the directory below.
    */
    Map<String, PrivilegeChangeTargetDto> known = knownIdentities(unnamed);
    Map<String, PermissionAuditUserDto> resolved = new HashMap<>();
    for (PermissionAuditUserDto user : unnamed) {
      PrivilegeChangeTargetDto found = known.get(user.targetUserGuid());
      if (found != null) {
        PermissionAuditUserDto named = merged(user, found);
        resolved.put(user.targetUserGuid(), named);
        cache(named);
      }
    }

    List<PermissionAuditUserDto> stillUnnamed = unnamed.stream()
        .filter(user -> !resolved.containsKey(user.targetUserGuid()))
        .toList();

    if (stillUnnamed.isEmpty()) {
      return replaced(users, resolved);
    }

    DirectoryEnv directory;
    try {
      directory = apiInstanceEnvResolver.resolveDirectory(cssEnvironment);
    } catch (RuntimeException e) {
      log.warn("Cannot choose a directory for {}; the list will show usernames only.",
          cssEnvironment);
      return replaced(users, resolved);
    }

    int looked = 0;
    for (PermissionAuditUserDto user : stillUnnamed) {
      if (looked++ >= MAX_NAME_LOOKUPS) {
        log.warn("{} user(s) in this trail have no recorded name; resolved the first {} and "
            + "left the rest showing their username.", stillUnnamed.size(), MAX_NAME_LOOKUPS);
        break;
      }
      try {
        named(user, directory).ifPresent(named -> {
          resolved.put(user.targetUserGuid(), named);
          /*
              Written back so the next visit does not pay for the same lookup.

              Only the gap is filled - see fillMissingTargetDetails - so a
              snapshot the trail already holds is never overwritten by a
              present-day answer, and re-running changes nothing.
          */
          cache(named);
        });
      } catch (RuntimeException e) {
        // One failure is enough to know the rest will fail the same way, and
        // somebody is waiting for a table to render.
        log.warn("Could not resolve names from the directory; the list will show usernames "
            + "only. Reason: {}", e.getMessage());
        break;
      }
    }

    return replaced(users, resolved);
  }

  /** The list with each resolved person swapped in. */
  private static List<PermissionAuditUserDto> replaced(
      List<PermissionAuditUserDto> users, Map<String, PermissionAuditUserDto> resolved) {

    if (resolved.isEmpty()) {
      return users;
    }
    return users.stream()
        .map(user -> resolved.getOrDefault(user.targetUserGuid(), user))
        .toList();
  }

  /** What the trail already holds about these people, keyed by GUID. */
  private Map<String, PrivilegeChangeTargetDto> knownIdentities(
      List<PermissionAuditUserDto> unnamed) {

    Map<String, PrivilegeChangeTargetDto> known = new HashMap<>();
    try {
      List<String> guids = unnamed.stream()
          .map(PermissionAuditUserDto::targetUserGuid).distinct().toList();
      for (Object[] row : auditRepository.findKnownIdentities(guids)) {
        PrivilegeChangeTargetDto details = readTargetDetails(row[1]);
        if (row[0] != null && details != null) {
          known.put(row[0].toString(), details);
        }
      }
    } catch (RuntimeException e) {
      // Best effort, like everything else here: the directory pass below still
      // runs, and the list is correct without either.
      log.warn("Could not read known identities from the trail: {}", e.getMessage());
    }
    return known;
  }

  /** The row, taking a name from what was found and keeping its own username. */
  private static PermissionAuditUserDto merged(
      PermissionAuditUserDto user, PrivilegeChangeTargetDto found) {

    return new PermissionAuditUserDto(
        user.targetUserGuid(), user.targetUserType(),
        notBlank(user.username()) ? user.username() : found.username(),
        found.firstName(), found.lastName(), found.email(), user.lastChangeDate());
  }

  /** Remember it, so the next visit needs neither the query nor the directory. */
  private void cache(PermissionAuditUserDto user) {
    auditWriteService.cacheTargetDetails(
        AuditUser.of(user.targetUserType(), user.targetUserGuid()),
        new PrivilegeChangeTargetDto(user.targetUserGuid(), user.username(),
            user.firstName(), user.lastName(), user.email()));
  }

  /** A row the trail already names needs nothing from the directory. */
  private static boolean hasName(PermissionAuditUserDto user) {
    return notBlank(user.firstName()) || notBlank(user.lastName()) || notBlank(user.email());
  }

  private static boolean notBlank(String value) {
    return value != null && !value.isBlank();
  }

  /** The same row, with whatever the directory knows about them filled in. */
  private Optional<PermissionAuditUserDto> named(
      PermissionAuditUserDto user, DirectoryEnv directory) {

    if (user.targetUserType() == UserType.IDIR) {
      return userLookupClient.getIdirDetailByGuid(directory, user.targetUserGuid())
          .map(found -> new PermissionAuditUserDto(
              user.targetUserGuid(), user.targetUserType(),
              notBlank(user.username()) ? user.username() : found.userId(),
              found.firstName(), found.lastName(), found.email(), user.lastChangeDate()));
    }
    return userLookupClient
        .getBusinessBceid(directory, UserLookupClient.SearchBy.USER_GUID, user.targetUserGuid())
        .map(found -> new PermissionAuditUserDto(
            user.targetUserGuid(), user.targetUserType(),
            notBlank(user.username()) ? user.username() : found.userId(),
            found.firstName(), found.lastName(), found.email(), user.lastChangeDate()));
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
