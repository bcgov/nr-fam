package ca.bc.gov.nrs.fam.security;

import static org.assertj.core.api.Assertions.assertThat;

import ca.bc.gov.nrs.fam.constants.UserType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("AuditUser (create_user / update_user format)")
class AuditUserTest {

  private static final String GUID = "A1B2C3D4E5F60718293A4B5C6D7E8F90";

  @Test
  @DisplayName("names the directory alongside the GUID")
  void prefixesWithTheUserType() {
    // The whole point: a GUID on its own does not say which directory to look in,
    // and an audit column has no user_type_code beside it to say so.
    assertThat(AuditUser.of(UserType.IDIR, GUID)).isEqualTo("IDIR\\" + GUID);
    assertThat(AuditUser.of(UserType.BCEID, GUID)).isEqualTo("BCEID_BUS\\" + GUID);
  }

  @Test
  @DisplayName("uses the user_type_code verbatim, so the value joins back to the code table")
  void prefixIsTheUserTypeCode() {
    for (UserType type : UserType.values()) {
      assertThat(AuditUser.of(type, GUID))
          .startsWith(type.getCode() + AuditUser.SEPARATOR);
    }
  }

  @Test
  @DisplayName("upper cases the GUID so the same person yields one value")
  void normalisesTheGuid() {
    // The directory and CSS disagree on case; two spellings of one person would
    // read as two people in the audit trail.
    assertThat(AuditUser.of(UserType.IDIR, GUID.toLowerCase()))
        .isEqualTo("IDIR\\" + GUID);
    assertThat(AuditUser.of(UserType.IDIR, "  " + GUID + "  "))
        .isEqualTo("IDIR\\" + GUID);
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"   "})
  @DisplayName("falls back to system rather than emitting a bare prefix")
  void missingGuidYieldsSystem(String guid) {
    // "IDIR\" alone would look like a real value and join to nothing.
    assertThat(AuditUser.of(UserType.IDIR, guid)).isEqualTo("system");
  }

  @Test
  @DisplayName("falls back to system when the type is unknown")
  void missingTypeYieldsSystem() {
    assertThat(AuditUser.of(null, GUID)).isEqualTo("system");
  }

  @Test
  @DisplayName("a null requester is a system write, not a crash")
  void nullRequesterIsSystem() {
    // Internal callers reach the audit write path with no requester.
    assertThat(AuditUser.of((Requester) null)).isEqualTo("system");
  }

  @Test
  @DisplayName("takes the identity from the requester")
  void formatsARequester() {
    Requester requester =
        Requester.builder().userType(UserType.IDIR).userGuid(GUID).userName("JSMITH").build();

    // The user name is deliberately not used: names get reassigned, GUIDs do not.
    assertThat(AuditUser.of(requester)).isEqualTo("IDIR\\" + GUID);
  }

  @Test
  @DisplayName("fits the column")
  void fitsTheColumn() {
    assertThat(AuditUser.of(UserType.BCEID, GUID).length())
        .isLessThanOrEqualTo(100);
  }
}
