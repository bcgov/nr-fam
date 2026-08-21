package ca.bc.gov.nrs.fam.service;

import ca.bc.gov.nrs.fam.constants.ErrorCode;
import ca.bc.gov.nrs.fam.constants.FamAdminRole;
import ca.bc.gov.nrs.fam.constants.UserType;
import ca.bc.gov.nrs.fam.dto.CssBulkGrantPreviewDto;
import ca.bc.gov.nrs.fam.dto.CssBulkGrantRowDto;
import ca.bc.gov.nrs.fam.dto.CssRoleOptionDto;
import ca.bc.gov.nrs.fam.dto.CssUserRoleAssignmentRequest;
import ca.bc.gov.nrs.fam.dto.CssUserRoleAssignmentResult;
import ca.bc.gov.nrs.fam.dto.UserLookupBceidUserDto;
import ca.bc.gov.nrs.fam.dto.UserLookupIdirUserDto;
import ca.bc.gov.nrs.fam.exception.FamHttpException;
import ca.bc.gov.nrs.fam.integration.UserLookupClient;
import ca.bc.gov.nrs.fam.security.AuthorizationService;
import ca.bc.gov.nrs.fam.security.Requester;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Granting many users a role at once, from a two-column CSV.
 *
 * <pre>
 * user_guid,role
 * AABBCCDDEEFF00112233445566778899,FSPTS_VIEW_ALL
 * </pre>
 *
 * <p>Deliberately the smallest file a person can be asked to produce. Everything
 * else the confirmation shows - names, organisations, the role's display name -
 * is resolved here, because those are what an uploader can actually check, and a
 * table of GUIDs and codes confirms nothing.
 *
 * <p><b>Two passes, and the second does not trust the first.</b> The preview
 * writes nothing; the apply re-reads and re-validates the same file. A preview
 * travels out through the browser, and granting what comes back would let an
 * edited payload grant something the checks never saw.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BulkGrantService {

  /**
   * Most rows accepted in one upload.
   *
   * <p>Each row costs a directory lookup, and the directory is SOAP-backed and
   * slow. A cap keeps one upload from becoming hundreds of upstream calls. It is
   * a refusal, not a truncation: silently granting the first 200 rows of a longer
   * file is the worst outcome available.
   */
  private static final int MAX_ROWS = 200;

  /**
   * The UTF-8 byte-order mark a spreadsheet writes at the start of a CSV.
   *
   * <p>Named rather than written as a literal: it is invisible in a source file,
   * so an editor or a merge could drop it without anyone noticing, and the
   * failure would be a first row that mysteriously will not parse.
   */
  private static final String BOM = "\uFEFF";

  private final CssIntegrationService cssIntegrationService;
  private final UserLookupClient userLookupClient;
  private final AuthorizationService authorizationService;
  private final ca.bc.gov.nrs.fam.security.TargetOrganizationGuard targetOrganizationGuard;

  /** What the upload would do. Writes nothing. */
  public CssBulkGrantPreviewDto preview(
      int integrationId, String environment, String csv, Requester requester) {

    return CssBulkGrantPreviewDto.of(validate(integrationId, environment, csv, requester));
  }

  /**
   * Grant every row that validates.
   *
   * <p>Rows are applied one at a time and reported one at a time: a file of fifty
   * grants where the twentieth fails should still deliver the other forty-nine,
   * and say which one did not. That is the opposite of the all-or-nothing rule
   * the single grant path uses for one person's several scope values, because
   * here the rows are unrelated people.
   *
   * <p>Invalid rows are not attempted. They are returned with their reason, so a
   * caller that skipped the preview still learns what was left out.
   */
  public List<CssBulkGrantRowDto> apply(
      int integrationId, String environment, String csv, Requester requester) {

    List<CssBulkGrantRowDto> rows = validate(integrationId, environment, csv, requester);
    List<CssBulkGrantRowDto> outcomes = new ArrayList<>();

    for (CssBulkGrantRowDto row : rows) {
      if (!row.valid()) {
        outcomes.add(row);
        continue;
      }
      outcomes.add(grantOne(integrationId, environment, row, requester));
    }

    long granted = outcomes.stream().filter(CssBulkGrantRowDto::valid).count();
    log.info("Bulk grant on integration {} ({}): {} of {} row(s) granted by {}.",
        integrationId, environment, granted, outcomes.size(), requester.userName());

    return outcomes;
  }

  private CssBulkGrantRowDto grantOne(
      int integrationId, String environment, CssBulkGrantRowDto row, Requester requester) {

    try {
      List<CssUserRoleAssignmentResult> results = cssIntegrationService.assignUserRoles(
          integrationId, environment,
          new CssUserRoleAssignmentRequest(
              row.userGuid(), row.userType(), row.roleCode(), row.email(), null, List.of()),
          requester);

      Optional<CssUserRoleAssignmentResult> failure =
          results.stream().filter(result -> !result.assigned()).findFirst();

      if (failure.isPresent()) {
        return withError(row, failure.get().errorMessage() == null
            ? "The role could not be assigned."
            : failure.get().errorMessage());
      }
      return row;

    } catch (FamHttpException e) {
      // A refusal that applies to this row alone - a self-grant, another
      // organisation, a role this requester may not grant.
      return withError(row, e.getDescription());
    } catch (RuntimeException e) {
      log.warn("Bulk grant row {} failed: {}", row.lineNumber(), e.getMessage());
      return withError(row, e.getMessage());
    }
  }

  // ---------------------------------------------------------------------------
  // Validation
  // ---------------------------------------------------------------------------

  private List<CssBulkGrantRowDto> validate(
      int integrationId, String environment, String csv, Requester requester) {

    List<ParsedRow> parsed = parse(csv);

    if (parsed.isEmpty()) {
      throw FamHttpException.badRequest(ErrorCode.INVALID_REQUEST_PARAMETER,
          "The file has no rows. Expected two columns: a user GUID and a role.");
    }
    if (parsed.size() > MAX_ROWS) {
      throw FamHttpException.badRequest(ErrorCode.INVALID_REQUEST_PARAMETER,
          "The file has %d rows; the most that can be uploaded at once is %d."
              .formatted(parsed.size(), MAX_ROWS));
    }

    // One read of the application's roles for the whole file, rather than per row.
    Map<String, CssRoleOptionDto> roles = cssIntegrationService
        .getRoles(integrationId, environment).stream()
        .collect(Collectors.toMap(
            role -> role.name().toUpperCase(Locale.ROOT),
            Function.identity(),
            (first, second) -> first));

    Set<String> seen = new LinkedHashSet<>();
    List<CssBulkGrantRowDto> rows = new ArrayList<>();

    for (ParsedRow row : parsed) {
      rows.add(validateRow(integrationId, environment, row, roles, seen, requester));
    }
    return rows;
  }

  private CssBulkGrantRowDto validateRow(
      int integrationId, String environment, ParsedRow row,
      Map<String, CssRoleOptionDto> roles, Set<String> seen, Requester requester) {

    if (row.userGuid().isEmpty() || row.roleCode().isEmpty()) {
      return CssBulkGrantRowDto.invalid(row.lineNumber(), row.userGuid(), row.roleCode(),
          "Both a user GUID and a role are required.");
    }

    // The same pair twice would grant once and report twice, which reads as a
    // silent failure on the second.
    if (!seen.add(row.userGuid().toUpperCase(Locale.ROOT) + "|"
        + row.roleCode().toUpperCase(Locale.ROOT))) {
      return CssBulkGrantRowDto.invalid(row.lineNumber(), row.userGuid(), row.roleCode(),
          "Duplicate of an earlier row in this file.");
    }

    // Administrative roles are appointments, not access. They are granted from
    // the administrator screens, which apply the tier rules; letting a CSV do it
    // would route around them entirely.
    if (FamAdminRole.isAdminRole(row.roleCode())) {
      return CssBulkGrantRowDto.invalid(row.lineNumber(), row.userGuid(), row.roleCode(),
          "%s is an administrative role. Appoint administrators from the "
              .formatted(row.roleCode())
              + "Delegated admins or Application admins tab, not by upload.");
    }

    CssRoleOptionDto role = roles.get(row.roleCode().toUpperCase(Locale.ROOT));
    if (role == null) {
      return CssBulkGrantRowDto.invalid(row.lineNumber(), row.userGuid(), row.roleCode(),
          "No role named %s exists in this application.".formatted(row.roleCode()));
    }

    // A scoped role is granted as one role per district or forest client, and the
    // file has nowhere to say which. Granting the base role instead would assign
    // something no application authorises on.
    if (role.roleTypeDistrict() || role.roleTypeClient()) {
      return CssBulkGrantRowDto.invalid(row.lineNumber(), row.userGuid(), row.roleCode(),
          "%s needs a %s chosen when it is granted, which this file cannot express. "
              .formatted(row.roleCode(), role.roleTypeDistrict() ? "district" : "forest client")
              + "Grant it from Add permission instead.");
    }

    // The directory is the only thing that can say whether a GUID is a real
    // person, and which of the two directories they are in.
    Resolved resolved = resolve(row.userGuid());
    if (resolved == null) {
      return CssBulkGrantRowDto.invalid(row.lineNumber(), row.userGuid(), row.roleCode(),
          "No IDIR or Business BCeID user has this GUID.");
    }

    CssBulkGrantRowDto candidate = new CssBulkGrantRowDto(
        row.lineNumber(), row.userGuid(), row.roleCode(),
        resolved.userType(), resolved.userName(), resolved.firstName(), resolved.lastName(),
        resolved.email(), resolved.organization(),
        role.displayName(), true, null);

    // The same per-row rules the single grant path applies, checked now so the
    // confirmation is honest rather than discovered halfway through applying.
    try {
      authorizationService.forbidSelfGrant(requester, row.userGuid());
      authorizationService.requireGrantableRoles(
          requester, integrationId, environment, List.of(role.name()));
      // A Business BCeID uploader may only grant within their own organisation.
      // The apply step enforces this anyway, inside the grant path - checking it
      // here too is what keeps the confirmation honest, rather than showing a row
      // as grantable and refusing it on submit.
      targetOrganizationGuard.requireSameOrganization(
          requester, resolved.userType(), row.userGuid());
    } catch (FamHttpException e) {
      return withError(candidate, e.getDescription());
    }

    return candidate;
  }

  /**
   * Which directory a GUID belongs to, and who it is.
   *
   * <p>IDIR first, then Business BCeID. The file carries no user type - two
   * columns was the requirement - so it has to be discovered, and the answer is
   * shown in the confirmation so the uploader can see which directory matched.
   *
   * <p>Returns null when neither knows the GUID, which is the check that "the
   * GUID exists" amounts to.
   */
  private Resolved resolve(String userGuid) {
    try {
      Optional<UserLookupIdirUserDto> idir = userLookupClient.getIdirDetailByGuid(userGuid);
      if (idir.isPresent()) {
        UserLookupIdirUserDto user = idir.get();
        return new Resolved(UserType.IDIR, user.userId(),
            user.firstName(), user.lastName(), user.email(), null);
      }
    } catch (RuntimeException e) {
      log.warn("IDIR lookup failed for {}: {}", userGuid, e.getMessage());
    }

    try {
      Optional<UserLookupBceidUserDto> bceid = userLookupClient.getBusinessBceid(
          UserLookupClient.SearchBy.USER_GUID, userGuid);
      if (bceid.isPresent()) {
        UserLookupBceidUserDto user = bceid.get();
        return new Resolved(UserType.BCEID, user.userId(),
            user.firstName(), user.lastName(), user.email(), user.businessLegalName());
      }
    } catch (RuntimeException e) {
      log.warn("Business BCeID lookup failed for {}: {}", userGuid, e.getMessage());
    }

    return null;
  }

  private static CssBulkGrantRowDto withError(CssBulkGrantRowDto row, String error) {
    return new CssBulkGrantRowDto(
        row.lineNumber(), row.userGuid(), row.roleCode(), row.userType(), row.userName(),
        row.firstName(), row.lastName(), row.email(), row.organization(),
        row.roleDisplayName(), false, error);
  }

  private record Resolved(
      UserType userType, String userName, String firstName, String lastName,
      String email, String organization) {}

  // ---------------------------------------------------------------------------
  // Parsing
  // ---------------------------------------------------------------------------

  private record ParsedRow(int lineNumber, String userGuid, String roleCode) {}

  /**
   * Two columns, comma separated.
   *
   * <p>Hand-written rather than pulled from a CSV library: the format is two
   * fields with no quoting, embedded commas or newlines - a GUID is hex and a
   * role code is upper-case letters, digits and underscores - so a parser would
   * be more to get wrong than to get right.
   *
   * <p>A header line is skipped if present, blank lines are ignored, and a
   * trailing byte-order mark is stripped: all three are what a spreadsheet
   * produces without being asked.
   */
  private static List<ParsedRow> parse(String csv) {
    if (csv == null || csv.isBlank()) {
      return List.of();
    }

    List<ParsedRow> rows = new ArrayList<>();
    String[] lines = csv.replace(BOM, "").split("\\R");

    for (int i = 0; i < lines.length; i++) {
      String line = lines[i].trim();
      if (line.isEmpty()) {
        continue;
      }

      String[] fields = line.split(",", -1);
      String first = fields[0].trim();
      String second = fields.length > 1 ? fields[1].trim() : "";

      // A header only counts as one on the first non-blank line, so a role
      // legitimately called something like "ROLE" further down is not dropped.
      if (rows.isEmpty() && isHeader(first, second)) {
        continue;
      }

      rows.add(new ParsedRow(i + 1, first, second));
    }
    return rows;
  }

  private static boolean isHeader(String first, String second) {
    String a = first.toLowerCase(Locale.ROOT).replace("_", "").replace(" ", "");
    String b = second.toLowerCase(Locale.ROOT).replace("_", "").replace(" ", "");
    return (a.equals("userguid") || a.equals("guid"))
        || (b.equals("role") || b.equals("rolecode") || b.equals("rolename"));
  }
}
