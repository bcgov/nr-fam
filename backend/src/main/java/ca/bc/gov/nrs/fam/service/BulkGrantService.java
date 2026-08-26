package ca.bc.gov.nrs.fam.service;

import ca.bc.gov.nrs.fam.constants.ErrorCode;
import ca.bc.gov.nrs.fam.constants.FamAdminRole;
import ca.bc.gov.nrs.fam.integration.ForestClientIntegrationService;
import ca.bc.gov.nrs.fam.dto.CssScopeSelection;
import ca.bc.gov.nrs.fam.constants.FamConstants;
import ca.bc.gov.nrs.fam.constants.District;
import ca.bc.gov.nrs.fam.constants.Region;
import ca.bc.gov.nrs.fam.constants.UserType;
import ca.bc.gov.nrs.fam.dto.CssBulkGrantPreviewDto;
import ca.bc.gov.nrs.fam.dto.CssBulkGrantRowDto;
import ca.bc.gov.nrs.fam.dto.CssRoleNaming;
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
import java.util.LinkedHashMap;
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
  private final ForestClientIntegrationService forestClientIntegrationService;
  private final ApiInstanceEnvResolver apiInstanceEnvResolver;

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
              row.userGuid(), row.userType(), row.roleCode(), row.email(), scopesOf(row)),
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

  /**
   * The scopes one validated row grants for.
   *
   * <p>One value per dimension, because one row is one grant. The grant path
   * still expands a cross-product, but with a single value each that product is
   * the row itself - which is what keeps the file readable line by line.
   */
  private static List<CssScopeSelection> scopesOf(CssBulkGrantRowDto row) {
    List<CssScopeSelection> scopes = new ArrayList<>();
    if (row.district() != null) {
      scopes.add(new CssScopeSelection(CssRoleNaming.SCOPE_DISTRICT, List.of(row.district())));
    }
    if (row.region() != null) {
      scopes.add(new CssScopeSelection(CssRoleNaming.SCOPE_REGION, List.of(row.region())));
    }
    if (row.forestClientNumber() != null) {
      scopes.add(new CssScopeSelection(
          CssRoleNaming.SCOPE_FOREST_CLIENT, List.of(row.forestClientNumber())));
    }
    return scopes;
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

    // One upstream call for every organisation the file mentions, not one per row.
    Map<String, ForestClient> clients = resolveClients(parsed, environment);

    Set<String> seen = new LinkedHashSet<>();
    List<CssBulkGrantRowDto> rows = new ArrayList<>();

    for (ParsedRow row : parsed) {
      rows.add(validateRow(
          integrationId, environment, row, roles, clients, seen, requester));
    }
    return rows;
  }

  private CssBulkGrantRowDto validateRow(
      int integrationId, String environment, ParsedRow row,
      Map<String, CssRoleOptionDto> roles, Map<String, ForestClient> clients,
      Set<String> seen, Requester requester) {

    String district = row.district().toUpperCase(Locale.ROOT);
    String region = row.region().toUpperCase(Locale.ROOT);
    String clientNumber = padClientNumber(row.forestClient());

    if (row.userGuid().isEmpty() || row.roleCode().isEmpty()) {
      return invalid(row, district, region, clientNumber,
          "Both a user GUID and a role are required.");
    }

    // The key includes the scope: the same person may legitimately get the same
    // role for two districts, which is two rows, but not the same district
    // twice - that would grant once and report twice, reading as a silent
    // failure on the second.
    if (!seen.add(String.join("|", row.userGuid().toUpperCase(Locale.ROOT),
        row.roleCode().toUpperCase(Locale.ROOT), district, region, clientNumber))) {
      return invalid(row, district, region, clientNumber,
          "Duplicate of an earlier row in this file.");
    }

    // Administrative roles are appointments, not access. They are granted from
    // the administrator screens, which apply the tier rules; letting a CSV do it
    // would route around them entirely.
    if (FamAdminRole.isAdminRole(row.roleCode())) {
      return invalid(row, district, region, clientNumber,
          "%s is an administrative role. Appoint administrators from the "
              .formatted(row.roleCode())
              + "Delegated admins or Application admins tab, not by upload.");
    }

    CssRoleOptionDto role = roles.get(row.roleCode().toUpperCase(Locale.ROOT));
    if (role == null) {
      return invalid(row, district, region, clientNumber,
          "No role named %s exists in this application.".formatted(row.roleCode()));
    }

    // ---- scope, in both directions -----------------------------------------
    //
    // A scope on a role that does not take one is the more dangerous mistake of
    // the two: the value would simply be ignored, and the row would grant wider
    // access than the file appears to ask for. It is refused rather than
    // dropped.
    if (!district.isEmpty() && !role.roleTypeDistrict()) {
      return invalid(row, district, region, clientNumber,
          "%s is not granted per district, so the district column must be empty."
              .formatted(row.roleCode()));
    }
    if (!region.isEmpty() && !role.roleTypeRegion()) {
      return invalid(row, district, region, clientNumber,
          "%s is not granted per region, so the region column must be empty."
              .formatted(row.roleCode()));
    }
    if (!clientNumber.isEmpty() && !role.roleTypeClient()) {
      return invalid(row, district, region, clientNumber,
          "%s is not granted per organization, so the organization column must be empty."
              .formatted(row.roleCode()));
    }
    // The other direction: without the scope the grant would name a role no
    // application authorises on, so it is refused rather than granted unscoped.
    if (role.roleTypeDistrict() && district.isEmpty()) {
      return invalid(row, district, region, clientNumber,
          "%s is granted per district. Put a district code in the third column."
              .formatted(row.roleCode()));
    }
    if (role.roleTypeRegion() && region.isEmpty()) {
      return invalid(row, district, region, clientNumber,
          "%s is granted per region. Put a region code in the region column."
              .formatted(row.roleCode()));
    }
    if (role.roleTypeClient() && clientNumber.isEmpty()) {
      return invalid(row, district, region, clientNumber,
          "%s is granted per organization. Put a client number in the fourth column."
              .formatted(row.roleCode()));
    }

    String districtName = null;
    if (!district.isEmpty()) {
      District known = District.fromOrgUnitCode(district).orElse(null);
      if (known == null) {
        return invalid(row, district, region, clientNumber,
            "%s is not a natural resource district code.".formatted(district));
      }
      // Expired districts are kept out of the picker so none can be granted;
      // the file must not be a way around that.
      if (known.isExpired()) {
        return invalid(row, district, region, clientNumber,
            "District %s (%s) has expired and cannot be granted."
                .formatted(district, known.getOrgUnitName()));
      }
      districtName = known.getOrgUnitName();
    }

    String regionName = null;
    if (!region.isEmpty()) {
      Region known = Region.fromRegionCode(region).orElse(null);
      if (known == null) {
        return invalid(row, district, region, clientNumber,
            "%s is not a natural resource region code.".formatted(region));
      }
      // Expired regions are kept out of the picker so none can be granted; the
      // file must not be a way around that.
      if (known.isExpired()) {
        return invalid(row, district, region, clientNumber,
            "Region %s (%s) has expired and cannot be granted."
                .formatted(region, known.getRegionName()));
      }
      regionName = known.getRegionName();
    }

    String clientName = null;
    if (!clientNumber.isEmpty()) {
      ForestClient client = clients.get(clientNumber);
      if (client == null) {
        return invalid(row, district, region, clientNumber,
            "No organization has client number %s.".formatted(clientNumber));
      }
      // Same rule the picker applies: an inactive organisation is findable but
      // refused on selection, so granting one by upload would be a dead end.
      if (!client.active()) {
        return invalid(row, district, region, clientNumber,
            "Organization %s (%s) is not active and cannot be granted."
                .formatted(clientNumber, client.name()));
      }
      clientName = client.name();
    }

    // Which directory the file says this GUID is in. Optional - blank means
    // "look in both" - but stating it is worth a column: a GUID is opaque, the
    // two directories are searched in turn, and a Business BCeID user therefore
    // costs a failed IDIR lookup on every row before being found.
    UserType stated;
    try {
      stated = parseUserType(row.userType());
    } catch (IllegalArgumentException e) {
      return invalid(row, district, region, clientNumber,
          "%s is not a user type. Use IDIR or BCEID, or leave the column empty."
              .formatted(row.userType()));
    }

    // The directory is the only thing that can say whether a GUID is a real
    // person, and which of the two directories they are in.
    Resolved resolved = resolve(row.userGuid(), stated);
    if (resolved == null) {
      return invalid(row, district, region, clientNumber, stated == null
          ? "No IDIR or Business BCeID user has this GUID."
          // Named rather than falling back to the other directory: the file said
          // which one, and quietly granting a different person who happens to
          // share the GUID is worse than refusing the row.
          : "No %s user has this GUID.".formatted(stated.getCode()));
    }

    CssBulkGrantRowDto candidate = new CssBulkGrantRowDto(
        row.lineNumber(), row.userGuid(), row.roleCode(),
        resolved.userType(), resolved.userName(), resolved.firstName(), resolved.lastName(),
        resolved.email(), resolved.organization(),
        role.displayName(),
        district.isEmpty() ? null : district, districtName,
        region.isEmpty() ? null : region, regionName,
        clientNumber.isEmpty() ? null : clientNumber, clientName,
        true, null);

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

  private static CssBulkGrantRowDto invalid(
      ParsedRow row, String district, String region, String clientNumber, String error) {
    return CssBulkGrantRowDto.invalid(
        row.lineNumber(), row.userGuid(), row.roleCode(), district, region, clientNumber, error);
  }

  /**
   * Client numbers are eight characters, zero-padded.
   *
   * <p>Spreadsheets drop leading zeros from anything that looks like a number,
   * so "00001012" reaches us as "1012" whenever the file has been through Excel.
   * Padding here is not a convenience: without it every client-scoped row in
   * such a file would be rejected as unknown.
   */
  private static String padClientNumber(String value) {
    String trimmed = value == null ? "" : value.trim();
    if (trimmed.isEmpty() || !trimmed.chars().allMatch(Character::isDigit)) {
      return trimmed;
    }
    return trimmed.length() >= 8 ? trimmed : "0".repeat(8 - trimmed.length()) + trimmed;
  }

  /** One organisation, as the file needs to know it. */
  private record ForestClient(String name, boolean active) {}

  /**
   * Resolves every client number the file mentions, in one upstream call.
   *
   * <p>Per row it would be one Forest Client request each; a two-hundred row
   * file would spend two hundred round trips confirming names nobody disputed.
   * The API takes a list, so the whole file costs one.
   */
  private Map<String, ForestClient> resolveClients(
      List<ParsedRow> parsed, String environment) {

    List<String> numbers = parsed.stream()
        .map(row -> padClientNumber(row.forestClient()))
        .filter(number -> !number.isEmpty())
        .distinct()
        .toList();

    if (numbers.isEmpty()) {
      return Map.of();
    }

    Map<String, ForestClient> byNumber = new LinkedHashMap<>();
    try {
      for (Map<String, Object> found : forestClientIntegrationService.search(
          numbers, numbers.size(), apiInstanceEnvResolver.resolve(environment), true)) {

        String number = String.valueOf(found.get("clientNumber"));
        Object name = found.get("clientName");
        byNumber.put(number, new ForestClient(
            name == null ? number : String.valueOf(name),
            FamConstants.FOREST_CLIENT_STATUS_CODE_ACTIVE
                .equals(String.valueOf(found.get("clientStatusCode")))));
      }
    } catch (RuntimeException e) {
      // Every client-scoped row then reads "no organization has this number",
      // which is wrong but visible - and far better than applying a file whose
      // organisations were never checked.
      log.warn("Could not resolve forest clients for the upload: {}", e.getMessage());
    }
    return byNumber;
  }

  /**
   * Finds the person behind a GUID.
   *
   * <p>When the file named a directory only that one is searched, so a row
   * saying IDIR can never resolve to a Business BCeID user who happens to share
   * the GUID. When it did not, both are tried in turn, as they always were -
   * which costs a Business BCeID user a failed IDIR lookup on every row.
   */
  private Resolved resolve(String userGuid, UserType stated) {
    if (stated == null || stated == UserType.IDIR) {
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
    }

    if (stated == null || stated == UserType.BCEID) {
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
    }

    return null;
  }

  /**
   * The user type a row named, or null when the column was left empty.
   *
   * <p>Accepts {@code BCEID} as well as the stored {@code BCEID_BUS}: the column
   * is written by hand, and FAM admits only the business flavour anyway, so
   * demanding the longer form would reject files for no reason a person could
   * see.
   *
   * @throws IllegalArgumentException when the column holds something that is
   *     neither, which the caller turns into a message naming the value
   */
  private static UserType parseUserType(String value) {
    String wanted = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    if (wanted.isEmpty()) {
      return null;
    }
    if (wanted.equals("IDIR")) {
      return UserType.IDIR;
    }
    if (wanted.equals("BCEID") || wanted.equals("BCEID_BUS")
        || wanted.equals("BCEIDBUSINESS")) {
      return UserType.BCEID;
    }
    throw new IllegalArgumentException(value);
  }

  private static CssBulkGrantRowDto withError(CssBulkGrantRowDto row, String error) {
    return new CssBulkGrantRowDto(
        row.lineNumber(), row.userGuid(), row.roleCode(), row.userType(), row.userName(),
        row.firstName(), row.lastName(), row.email(), row.organization(),
        row.roleDisplayName(), row.district(), row.districtName(),
        row.region(), row.regionName(),
        row.forestClientNumber(), row.forestClientName(), false, error);
  }

  private record Resolved(
      UserType userType, String userName, String firstName, String lastName,
      String email, String organization) {}

  // ---------------------------------------------------------------------------
  // Parsing
  // ---------------------------------------------------------------------------

  private record ParsedRow(
      int lineNumber, String userGuid, String userType, String roleCode,
      String district, String forestClient, String region) {}

  /**
   * Splits the file into rows.
   *
   * <p>Six columns: user GUID, user type, role, district, organization, region.
   * The scope columns are blank for a role that is not scoped that way, and a
   * role scoped several ways carries each - one row is one grant, so a person
   * getting a role for three districts is three rows. That keeps the file
   * readable in a spreadsheet, which is where these are actually written.
   *
   * <p><b>Region is last rather than beside district, which is where it belongs
   * to read.</b> The parser is positional, so inserting a column would silently
   * reinterpret every file written before it - organisation numbers would arrive
   * as regions. Appending keeps every existing file valid, which is the same
   * reason trailing columns are optional: a two-column file written before
   * scopes were supported still uploads unchanged.
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
      String first = field(fields, 0);

      // A header only counts as one on the first non-blank line, so a role
      // legitimately called something like "ROLE" further down is not dropped.
      if (rows.isEmpty() && isHeader(first, field(fields, 1), field(fields, 2))) {
        continue;
      }
      rows.add(new ParsedRow(i + 1, first, field(fields, 1), field(fields, 2),
          field(fields, 3), field(fields, 4), field(fields, 5)));
    }
    return rows;
  }

  /** Missing trailing columns read as blank rather than as a malformed row. */
  private static String field(String[] fields, int index) {
    return index < fields.length ? fields[index].trim() : "";
  }

  /**
   * Whether the first row names its columns rather than carrying data.
   *
   * <p>Checks the role column in its new position <em>and</em> its old one, so a
   * header written before the user-type column was added is still recognised as
   * one rather than uploaded as a row.
   */
  private static boolean isHeader(String first, String second, String third) {
    String a = normalise(first);
    return a.equals("userguid") || a.equals("guid")
        || isRoleHeading(normalise(second)) || isRoleHeading(normalise(third));
  }

  private static boolean isRoleHeading(String value) {
    return value.equals("role") || value.equals("rolecode") || value.equals("rolename");
  }

  private static String normalise(String value) {
    return value.toLowerCase(Locale.ROOT).replace("_", "").replace(" ", "");
  }
}
