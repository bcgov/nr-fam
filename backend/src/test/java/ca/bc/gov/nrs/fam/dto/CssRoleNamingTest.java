package ca.bc.gov.nrs.fam.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import ca.bc.gov.nrs.fam.constants.UserType;
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
}
