package ca.bc.gov.nrs.fam.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import ca.bc.gov.nrs.fam.constants.UserType;
import java.util.List;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("CssRoleNaming (scope encoded into CSS role names)")
class CssRoleNamingTest {

  @ParameterizedTest(name = "{0} + {1} + {2} -> {3}")
  @CsvSource({
      "CHR_FREP_EDITOR, DISTRICT,      DCC,      CHR_FREP_EDITOR_DISTRICT-DCC",
      "FOM_SUBMITTER,   FOREST_CLIENT, 00001018, FOM_SUBMITTER_FOREST_CLIENT-00001018",
  })
  @DisplayName("builds a scope-specific role name")
  void buildsScopedName(String role, String scopeType, String value, String expected) {
    assertThat(CssRoleNaming.buildScopedRoleName(role, scopeType, value)).isEqualTo(expected);
  }

  @ParameterizedTest(name = "{0} -> ({1}, {2}, {3})")
  @CsvSource({
      "CHR_FREP_EDITOR_DISTRICT-DCC,         CHR_FREP_EDITOR, DISTRICT,      DCC",
      "FOM_SUBMITTER_FOREST_CLIENT-00001018, FOM_SUBMITTER,   FOREST_CLIENT, 00001018",
  })
  @DisplayName("parses a scope-specific role name back into its parts")
  void parsesScopedName(String roleName, String base, String scopeType, String value) {
    CssRoleNaming.ScopedRoleName parsed = CssRoleNaming.parse(roleName);

    assertThat(parsed.baseRoleName()).isEqualTo(base);
    assertThat(parsed.scopeType()).isEqualTo(scopeType);
    assertThat(parsed.scopeValue()).isEqualTo(value);
  }

  @Test
  @DisplayName("keeps a multi-word scope type intact")
  void keepsMultiWordScopeTypeIntact() {
    // The Python original split on the last underscore, which reads
    // FOM_SUBMITTER_FOREST_CLIENT-00001018 as scope type CLIENT of role
    // FOM_SUBMITTER_FOREST. The value survives but the role and scope type do
    // not, so the row renders against a role that does not exist.
    CssRoleNaming.ScopedRoleName parsed =
        CssRoleNaming.parse("FOM_SUBMITTER_FOREST_CLIENT-00001018");

    assertThat(parsed.baseRoleName()).isNotEqualTo("FOM_SUBMITTER_FOREST");
    assertThat(parsed.scopeType()).isNotEqualTo("CLIENT");
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "FREP_EDITOR",          // no scope at all
      "SOME-ROLE",            // a hyphen that is not a scope separator
      "PLAIN",
      "_DISTRICT-DCC",        // nothing before the scope type
  })
  @DisplayName("leaves a name that is not scope-encoded alone")
  void leavesUnscopedNamesAlone(String roleName) {
    CssRoleNaming.ScopedRoleName parsed = CssRoleNaming.parse(roleName);

    assertThat(parsed.baseRoleName()).isEqualTo(roleName);
    assertThat(parsed.scopeType()).isNull();
    assertThat(parsed.scopeValue()).isNull();
  }

  @ParameterizedTest(name = "{0}/{1} round-trips")
  @CsvSource({
      "CHR_FREP_EDITOR, DISTRICT,      DCC",
      "FOM_SUBMITTER,   FOREST_CLIENT, 00001018",
      "A_B_C,           DISTRICT,      DQU",
  })
  @DisplayName("round-trips build -> parse")
  void roundTrips(String role, String scopeType, String value) {
    CssRoleNaming.ScopedRoleName parsed =
        CssRoleNaming.parse(CssRoleNaming.buildScopedRoleName(role, scopeType, value));

    assertThat(parsed.baseRoleName()).isEqualTo(role);
    assertThat(parsed.scopeType()).isEqualTo(scopeType);
    assertThat(parsed.scopeValue()).isEqualTo(value);
  }

  @ParameterizedTest(name = "{0}/{1} -> {2}")
  @CsvSource({
      "AABBCCDDEEFF00112233445566778899, IDIR,  aabbccddeeff00112233445566778899@azureidir",
      "AABBCCDDEEFF00112233445566778899, BCEID, aabbccddeeff00112233445566778899@bceidbusiness",
  })
  @DisplayName("lowercases the GUID for the CSS username")
  void buildsUsername(String guid, UserType userType, String expected) {
    // FAM stores GUIDs upper case; Keycloak's federated usernames are lower case.
    assertThat(CssRoleNaming.buildUsername(guid, userType, "azureidir", "bceidbusiness"))
        .isEqualTo(expected);
  }

  @Test
  @DisplayName("writes a compound scope in a fixed order, whatever order it is given")
  void compoundNameIsCanonical() {
    // The name is the authorisation. Two spellings of the same pair would mean
    // granting it twice and revoking only one of them.
    String districtFirst = CssRoleNaming.buildScopedRoleName("FOM_SUBMITTER", List.of(
        new CssRoleNaming.Scope("DISTRICT", "DCC"),
        new CssRoleNaming.Scope("FOREST_CLIENT", "00001012")));
    String clientFirst = CssRoleNaming.buildScopedRoleName("FOM_SUBMITTER", List.of(
        new CssRoleNaming.Scope("FOREST_CLIENT", "00001012"),
        new CssRoleNaming.Scope("DISTRICT", "DCC")));

    assertThat(districtFirst).isEqualTo("FOM_SUBMITTER_DISTRICT-DCC_FOREST_CLIENT-00001012");
    assertThat(clientFirst).isEqualTo(districtFirst);
  }

  @Test
  @DisplayName("round-trips a region scope, underscores in the code and all")
  void roundTripsRegion() {
    // Region codes carry underscores - KOOTENAY_BOUNDARY - while the parser
    // splits the value off at the last hyphen. That is why the codes must not
    // contain one, and why this is worth pinning.
    String name = CssRoleNaming.buildScopedRoleName(
        "FREP_EDITOR", "REGION", "KOOTENAY_BOUNDARY");

    assertThat(name).isEqualTo("FREP_EDITOR_REGION-KOOTENAY_BOUNDARY");

    CssRoleNaming.ScopedRoleName parsed = CssRoleNaming.parse(name);
    assertThat(parsed.baseRoleName()).isEqualTo("FREP_EDITOR");
    assertThat(parsed.scopes())
        .containsExactly(new CssRoleNaming.Scope("REGION", "KOOTENAY_BOUNDARY"));
  }

  @Test
  @DisplayName("reads a district, region and client name back in written order")
  void parsesAllThreeScopes() {
    CssRoleNaming.ScopedRoleName parsed = CssRoleNaming.parse(
        "FOM_SUBMITTER_DISTRICT-DCC_REGION-CARIBOO_FOREST_CLIENT-00001012");

    assertThat(parsed.baseRoleName()).isEqualTo("FOM_SUBMITTER");
    assertThat(parsed.scopes()).containsExactly(
        new CssRoleNaming.Scope("DISTRICT", "DCC"),
        new CssRoleNaming.Scope("REGION", "CARIBOO"),
        new CssRoleNaming.Scope("FOREST_CLIENT", "00001012"));
  }

  @Test
  @DisplayName("writes the three scopes in one canonical order, whatever order they arrive in")
  void ordersThreeScopes() {
    String written = CssRoleNaming.buildScopedRoleName("FOM_SUBMITTER", List.of(
        new CssRoleNaming.Scope("FOREST_CLIENT", "00001012"),
        new CssRoleNaming.Scope("REGION", "CARIBOO"),
        new CssRoleNaming.Scope("DISTRICT", "DCC")));

    assertThat(written)
        .isEqualTo("FOM_SUBMITTER_DISTRICT-DCC_REGION-CARIBOO_FOREST_CLIENT-00001012");
  }

  @Test
  @DisplayName("maps the region scope type to its marker role")
  void markerForRegion() {
    assertThat(CssRoleNaming.markerFor("REGION")).contains(CssRoleNaming.MARKER_REGION);
    assertThat(CssRoleNaming.markersFor(List.of("REGION", "DISTRICT")))
        .containsExactly(CssRoleNaming.MARKER_DISTRICT, CssRoleNaming.MARKER_REGION);
  }

  @Test
  @DisplayName("reads both scopes back out of a compound name")
  void parsesCompoundName() {
    CssRoleNaming.ScopedRoleName parsed =
        CssRoleNaming.parse("FOM_SUBMITTER_DISTRICT-DCC_FOREST_CLIENT-00001012");

    assertThat(parsed.baseRoleName()).isEqualTo("FOM_SUBMITTER");
    assertThat(parsed.scopes()).containsExactly(
        new CssRoleNaming.Scope("DISTRICT", "DCC"),
        new CssRoleNaming.Scope("FOREST_CLIENT", "00001012"));
  }

  @Test
  @DisplayName("round-trips every combination")
  void roundTrips() {
    List<List<CssRoleNaming.Scope>> cases = List.of(
        List.of(),
        List.of(new CssRoleNaming.Scope("DISTRICT", "DCC")),
        List.of(new CssRoleNaming.Scope("FOREST_CLIENT", "00001012")),
        List.of(new CssRoleNaming.Scope("DISTRICT", "DCC"),
            new CssRoleNaming.Scope("FOREST_CLIENT", "00001012")));

    for (List<CssRoleNaming.Scope> scopes : cases) {
      String name = CssRoleNaming.buildScopedRoleName("FOM_SUBMITTER", scopes);
      CssRoleNaming.ScopedRoleName parsed = CssRoleNaming.parse(name);
      assertThat(parsed.baseRoleName()).as(name).isEqualTo("FOM_SUBMITTER");
      assertThat(parsed.scopes()).as(name).isEqualTo(scopes);
    }
  }

  @Test
  @DisplayName("a base role containing a hyphen is still not mistaken for a scope")
  void hyphenInBaseRoleSurvivesCompoundParsing() {
    // The peel-one-at-a-time loop must stop at a hyphen that is not a scope
    // separator, or a role like SOME-ROLE would lose its tail.
    CssRoleNaming.ScopedRoleName parsed =
        CssRoleNaming.parse("SOME-ROLE_DISTRICT-DCC");

    assertThat(parsed.baseRoleName()).isEqualTo("SOME-ROLE");
    assertThat(parsed.scopes())
        .containsExactly(new CssRoleNaming.Scope("DISTRICT", "DCC"));
  }

  @Test
  @DisplayName("markers are produced for every scope a role carries")
  void markersForBothScopes() {
    assertThat(CssRoleNaming.markersFor(List.of("FOREST_CLIENT", "DISTRICT")))
        .containsExactly(CssRoleNaming.MARKER_DISTRICT, CssRoleNaming.MARKER_FOREST_CLIENT);
    assertThat(CssRoleNaming.markersFor(List.of("DISTRICT")))
        .containsExactly(CssRoleNaming.MARKER_DISTRICT);
    assertThat(CssRoleNaming.markersFor(List.of())).isEmpty();
    assertThat(CssRoleNaming.markersFor(List.of("NONSENSE"))).isEmpty();
  }

  @Test
  @DisplayName("refuses a null user type, rather than inventing a provider")
  void refusesNullUserType() {
    // Every modelled user type now maps to a CSS provider, so the compiler covers
    // the unmapped case. Null is what is left, and it must not be guessed at: CSS
    // accepts a username that cannot exist without complaint, so the grant would
    // look successful while doing nothing.
    assertThatThrownBy(() ->
        CssRoleNaming.buildUsername("ABC", null, "azureidir", "bceidbusiness"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("uses the configured IDIR alias, which the standard realm needs")
  void usesConfiguredIdirAlias() {
    // Real CSS data shows BC Gov IDIR users as <guid>@azureidir. Assigning to
    // <guid>@idir targets a username that does not exist there.
    assertThat(CssRoleNaming.buildUsername(
        "AABB", UserType.IDIR, "azureidir", "bceidbusiness"))
        .isEqualTo("aabb@azureidir");
  }

  @Test
  @DisplayName("refuses to build a username with no alias, rather than guessing one")
  void refusesMissingAlias() {
    assertThatThrownBy(() -> CssRoleNaming.buildUsername("AABB", (String) null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest(name = "{0} -> {1}")
  @CsvSource({
      "abc@idir,           IDIR",
      "abc@azureidir,      IDIR",
      "abc@bceidbusiness,  BCEID",
      "abc@IDIR,           IDIR",
  })
  @DisplayName("maps the username suffix onto a FAM domain")
  void mapsDomain(String username, String expected) {
    assertThat(CssRoleNaming.domainFromUsername(username)).contains(expected);
  }

  @ParameterizedTest
  @ValueSource(strings = {"abc@bceidbasic", "abc@unknown", "no-at-sign", ""})
  @DisplayName("returns empty for a suffix FAM does not recognise")
  void unknownDomainIsEmpty(String username) {
    assertThat(CssRoleNaming.domainFromUsername(username)).isEmpty();
  }

  // --------------------------------------------------------------- descriptions

  @Test
  @DisplayName("round-trips a code and its description through a sidecar name")
  void roundTripsLabel() {
    String name = CssRoleNaming.buildLabelRoleName("FREP_ADMINISTRATOR", "FREP Administrator");

    assertThat(name).isEqualTo("FAM:LABEL:FREP_ADMINISTRATOR:FREP Administrator");
    assertThat(CssRoleNaming.parseLabel(name)).hasValueSatisfying(label -> {
      assertThat(label.roleCode()).isEqualTo("FREP_ADMINISTRATOR");
      assertThat(label.text()).isEqualTo("FREP Administrator");
    });
  }

  @Test
  @DisplayName("keeps a description that contains a colon whole")
  void keepsColonInDescription() {
    // Only the separator after the code is structural. Truncating somebody's
    // description at the next colon would silently lose half of it.
    String name = CssRoleNaming.buildLabelRoleName("FOM_SUBMITTER", "Submitter: FOM only");

    assertThat(CssRoleNaming.parseLabel(name))
        .hasValueSatisfying(label ->
            assertThat(label.text()).isEqualTo("Submitter: FOM only"));
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "FAM:LABEL:",                 // nothing at all
      "FAM:LABEL:FREP_ADMIN",       // a code but no description
      "FAM:LABEL:FREP_ADMIN:",      // an empty description
      "FAM:LABEL::A description",   // no code
  })
  @DisplayName("ignores a malformed sidecar rather than half-reading it")
  void ignoresMalformedLabel(String roleName) {
    assertThat(CssRoleNaming.parseLabel(roleName)).isEmpty();
  }

  @Test
  @DisplayName("an ordinary role is not a sidecar")
  void ordinaryRoleIsNotALabel() {
    assertThat(CssRoleNaming.isLabelRole("FREP_ADMINISTRATOR")).isFalse();
    assertThat(CssRoleNaming.parseLabel("FREP_ADMINISTRATOR")).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"FREP_ADMINISTRATOR", "FOM_SUBMITTER", "AB", "R2D2_ROLE"})
  @DisplayName("accepts a code that can be a role name, a scope prefix and a sidecar key")
  void acceptsValidCodes(String roleCode) {
    assertThat(CssRoleNaming.isValidRoleCode(roleCode)).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "Submitter (SLR)",   // spaces and parens - CSS allows them, the code may not
      "frep_admin",        // lower case
      "FREP-ADMIN",        // a hyphen would collide with the scope value separator
      "FREP:ADMIN",        // a colon would collide with the sidecar separator
      "1_FREP",            // must start with a letter
      "F",                 // too short to mean anything
      "",
  })
  @DisplayName("rejects a code that would break one of the naming conventions")
  void rejectsInvalidCodes(String roleCode) {
    assertThat(CssRoleNaming.isValidRoleCode(roleCode)).isFalse();
  }

  @Test
  @DisplayName("a sidecar never parses as a scoped role name")
  void sidecarIsNotMistakenForAScopedRole() {
    // Both conventions live in the same namespace, so they must not overlap: a
    // description ending in "...-something" must not read as a scope value.
    String name = CssRoleNaming.buildLabelRoleName("FOM_SUBMITTER", "Submitter - district");

    assertThat(CssRoleNaming.parse(name).scopeValue()).isNull();
  }

  @ParameterizedTest(name = "{0} -> {1}")
  @CsvSource({
      "DISTRICT,       HAS_DISTRICT_ROLE",
      "district,       HAS_DISTRICT_ROLE",
      "FOREST_CLIENT,  HAS_FOREST_CLIENT",
  })
  @DisplayName("maps a scope type onto its marker role")
  void mapsScopeTypeToMarker(String scopeType, String expected) {
    assertThat(CssRoleNaming.markerFor(scopeType)).contains(expected);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "  ", "SOMETHING_ELSE"})
  @DisplayName("has no marker for an unscoped or unknown scope type")
  void noMarkerForUnknownScope(String scopeType) {
    assertThat(CssRoleNaming.markerFor(scopeType)).isEmpty();
  }

  /**
   * The sidecar names have to fit inside a Keycloak role name.
   *
   * <p>Both fields are bounded independently, so nothing else notices when the
   * pieces stop adding up. They did not: a 200 character description on a role
   * code longer than 45 characters composed a name of up to 269 characters,
   * which Keycloak refused as an opaque upstream error naming neither field -
   * and only after the role itself had been created.
   */
  @Nested
  @DisplayName("sidecar length budget")
  class SidecarBudget {

    /** Keycloak's own ceiling on a role name. */
    private static final int KEYCLOAK_MAX_ROLE_NAME = 255;

    /** The longest code ROLE_CODE_PATTERN admits: one letter plus 58 more. */
    private static final String LONGEST_CODE = "A".repeat(59);

    private static String longest(int limit) {
      return "x".repeat(limit);
    }

    @Test
    @DisplayName("the longest description on the longest code still fits")
    void descriptionFits() {
      String name = CssRoleNaming.buildDescriptionRoleName(
          LONGEST_CODE, longest(180));

      assertThat(name.length())
          .as("FAM:DESC sidecar overflows a Keycloak role name")
          .isLessThanOrEqualTo(KEYCLOAK_MAX_ROLE_NAME);
    }

    @Test
    @DisplayName("the longest display name on the longest code still fits")
    void labelFits() {
      String name = CssRoleNaming.buildLabelRoleName(LONGEST_CODE, longest(150));

      assertThat(name.length())
          .as("FAM:LABEL sidecar overflows a Keycloak role name")
          .isLessThanOrEqualTo(KEYCLOAK_MAX_ROLE_NAME);
    }

    @Test
    @DisplayName("the longest code the pattern admits is the one budgeted for")
    void longestCodeIsTheOneBudgetedFor() {
      // The budget above is only right while this is the longest code that can
      // reach it. Lengthening the pattern silently invalidates both sums.
      assertThat(CssRoleNaming.isValidRoleCode(LONGEST_CODE)).isTrue();
      assertThat(CssRoleNaming.isValidRoleCode(LONGEST_CODE + "A"))
          .as("a longer code is accepted than the sidecar budget allows for")
          .isFalse();
    }
  }
}
