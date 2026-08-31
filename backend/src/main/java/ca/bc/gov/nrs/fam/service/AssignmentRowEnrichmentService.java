package ca.bc.gov.nrs.fam.service;

import ca.bc.gov.nrs.fam.dto.CssRoleNaming;
import ca.bc.gov.nrs.fam.dto.CssUserRoleRowDto;
import ca.bc.gov.nrs.fam.dto.UserLookupIdirUserDto;
import ca.bc.gov.nrs.fam.constants.DirectoryEnv;
import ca.bc.gov.nrs.fam.integration.UserLookupClient;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Puts names on the assignment rows CSS could only identify by GUID.
 *
 * <p>CSS returns a username, first name, last name and email for anyone who has
 * signed in - those attributes are populated by the sign-in itself. A user
 * created by a grant has never signed in, so CSS holds nothing but
 * {@code <guid>@azureidir}, and the permissions table would show a raw GUID with
 * empty name and email columns.
 *
 * <p>The directory can answer that, given a GUID. So only the rows CSS could not
 * name are looked up, deduplicated by GUID, which in practice is a handful even
 * on an application with many users.
 *
 * <p><b>Best effort, deliberately.</b> {@link UserLookupClient} otherwise raises
 * an upstream failure rather than returning an empty result, because its usual
 * caller is an administrator searching for somebody and "nobody matched" is a
 * materially different answer from "the directory is unreachable". Here the
 * opposite holds: the assignments are already known and correct, and a directory
 * outage should cost the display of a few names rather than the whole table. So
 * failures are logged and the row is left as CSS returned it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssignmentRowEnrichmentService {

  /**
   * Most distinct users to resolve for one listing.
   *
   * <p>Each is a separate call to a SOAP-backed directory, so an application with
   * a large backlog of never-signed-in users could otherwise turn one page load
   * into hundreds of upstream requests. What is skipped is logged rather than
   * dropped silently - a bounded listing that looks complete is worse than one
   * that says it is not.
   */
  private static final int MAX_LOOKUPS = 25;

  private static final String IDIR_DOMAIN = "IDIR";

  private final UserLookupClient userLookupClient;

  /**
   * Fill in the names CSS did not supply.
   *
   * <p>Returns the rows unchanged when nothing needs resolving, when the
   * directory is not configured, or when it cannot be reached.
   */
  public List<CssUserRoleRowDto> withResolvedNames(
      DirectoryEnv directory, List<CssUserRoleRowDto> rows) {

    if (!userLookupClient.isConfigured(directory)) {
      return rows;
    }

    Set<String> unresolved = new LinkedHashSet<>();
    for (CssUserRoleRowDto row : rows) {
      guidNeedingLookup(row).ifPresent(unresolved::add);
    }

    if (unresolved.isEmpty()) {
      return rows;
    }

    List<String> toLookUp = new ArrayList<>(unresolved);
    if (toLookUp.size() > MAX_LOOKUPS) {
      log.warn("{} users in this listing have no name in CSS; resolving the first {} against "
          + "the directory and leaving the rest showing their GUID.",
          toLookUp.size(), MAX_LOOKUPS);
      toLookUp = toLookUp.subList(0, MAX_LOOKUPS);
    }

    Map<String, UserLookupIdirUserDto> resolved = new HashMap<>();
    for (String guid : toLookUp) {
      try {
        userLookupClient.getIdirDetailByGuid(directory, guid)
            .ifPresent(user -> resolved.put(guid, user));
      } catch (RuntimeException e) {
        // One failure is enough to know the rest will fail the same way, and
        // this runs while somebody waits for a table to render.
        log.warn("Could not resolve names from the directory; the listing will show GUIDs "
            + "for users who have not signed in yet. Reason: {}", e.getMessage());
        break;
      }
    }

    if (resolved.isEmpty()) {
      return rows;
    }

    log.debug("Resolved {} of {} unnamed user(s) against the directory.",
        resolved.size(), unresolved.size());

    return rows.stream().map(row -> apply(row, resolved)).toList();
  }

  /**
   * The GUID to look up, when this row is an IDIR user CSS could not name.
   *
   * <p>A row with a name is left alone: CSS is the more current source for
   * somebody who has signed in, and resolving them again would cost a call per
   * user to change nothing.
   *
   * <p>BCeID rows are not resolved here even though the directory could - the
   * organisation rule that governs reading a Business BCeID user has no obvious
   * reading on a row the administrator is already entitled to see, and guessing
   * at it is worse than leaving it.
   */
  private static Optional<String> guidNeedingLookup(CssUserRoleRowDto row) {
    if (!IDIR_DOMAIN.equals(row.domain()) || hasName(row)) {
      return Optional.empty();
    }
    return CssRoleNaming.guidFromUsername(row.username());
  }

  private static boolean hasName(CssUserRoleRowDto row) {
    return notBlank(row.firstName()) || notBlank(row.lastName());
  }

  private static boolean notBlank(String value) {
    return value != null && !value.isBlank();
  }

  private static CssUserRoleRowDto apply(
      CssUserRoleRowDto row, Map<String, UserLookupIdirUserDto> resolved) {

    UserLookupIdirUserDto user = guidNeedingLookup(row)
        .map(resolved::get)
        .orElse(null);

    if (user == null) {
      return row;
    }

    // The user id is what an administrator recognises - JSMITH rather than a
    // GUID - and is what CSS itself reports once the person has signed in.
    return new CssUserRoleRowDto(
        notBlank(user.userId()) ? user.userId() : row.username(),
        row.userGuid(),
        row.domain(),
        user.firstName(),
        user.lastName(),
        user.email(),
        row.roleName(),
        row.roleDisplayName(),
        row.scopes(),
        // Carried through: these two rebuild a row to correct the name on it,
        // and dropping the expiry would quietly turn a temporary grant into a
        // permanent-looking one on the very rows that needed a lookup.
        row.expiresOn());
  }
}
