package ca.bc.gov.nrs.fam.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("IdimIdirUsersSearchParams (port of IdimProxyIdirUsersSearchParamReqSchema)")
class IdimIdirUsersSearchParamsTest {

  private static IdimIdirUsersSearchParams params() {
    return new IdimIdirUsersSearchParams();
  }

  @Test
  @DisplayName("rejects a search with no criteria, which would return an arbitrary slice")
  void rejectsEmptySearch() {
    assertThatThrownBy(() -> params().validate())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("At least one of firstName, lastName, or userId");
  }

  @Test
  @DisplayName("rejects a single-character term as too broad")
  void rejectsSingleCharacterTerm() {
    IdimIdirUsersSearchParams p = params();
    p.setLastName("S");

    assertThatThrownBy(p::validate)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("lastName must be at least 2 characters");
  }

  @Test
  @DisplayName("treats a blank term as absent")
  void blankIsTreatedAsAbsent() {
    IdimIdirUsersSearchParams p = params();
    p.setFirstName("   ");

    assertThat(p.getFirstName()).isNull();
    assertThatThrownBy(p::validate).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("trims surrounding whitespace")
  void trimsWhitespace() {
    IdimIdirUsersSearchParams p = params();
    p.setLastName("  Smith  ");

    assertThat(p.getLastName()).isEqualTo("Smith");
  }

  @Test
  @DisplayName("accepts a two-character term")
  void acceptsTwoCharacterTerm() {
    IdimIdirUsersSearchParams p = params();
    p.setLastName("Sm");

    assertThatCode(p::validate).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("matches names partially and user ids exactly")
  void appliesMatchModes() {
    IdimIdirUsersSearchParams p = params();
    p.setFirstName("Jan");
    p.setLastName("Smi");
    p.setUserId("JSMITH");

    assertThat(p.toQueryParams())
        .containsEntry("firstName", "Jan")
        .containsEntry("firstNameMatchMode", "Contains")
        .containsEntry("lastName", "Smi")
        .containsEntry("lastNameMatchMode", "Contains")
        .containsEntry("userId", "JSMITH")
        // A partial user id would return noise rather than a useful match.
        .containsEntry("userIdMatchMode", "Exact");
  }

  @Test
  @DisplayName("omits absent fields and their match modes from the query")
  void omitsAbsentFields() {
    IdimIdirUsersSearchParams p = params();
    p.setLastName("Smith");

    assertThat(p.toQueryParams())
        .containsOnlyKeys("lastName", "lastNameMatchMode", "pageSize");
  }

  @Test
  @DisplayName("always sends a page size, defaulting to the external API default")
  void alwaysSendsPageSize() {
    IdimIdirUsersSearchParams p = params();
    p.setUserId("JSMITH");

    assertThat(p.toQueryParams()).containsEntry("pageSize", 50);
  }
}
