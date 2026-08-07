package ca.bc.gov.nrs.fam.service;

import ca.bc.gov.nrs.fam.constants.UserType;
import static org.assertj.core.api.Assertions.assertThat;

import ca.bc.gov.nrs.fam.dto.FamApplicationDto;
import ca.bc.gov.nrs.fam.dto.FamApplicationUserRoleAssignmentGetDto;
import ca.bc.gov.nrs.fam.dto.FamForestClientDto;
import ca.bc.gov.nrs.fam.dto.FamRoleWithClientDto;
import ca.bc.gov.nrs.fam.dto.FamUserInfoDto;
import ca.bc.gov.nrs.fam.dto.FamUserTypeDto;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("UserRoleCsvExporter (port of __export_app_user_roles_csv_file)")
class UserRoleCsvExporterTest {

  private final UserRoleCsvExporter exporter = new UserRoleCsvExporter();

  private static final FamApplicationDto APPLICATION =
      new FamApplicationDto(1L, "FOM_DEV", "Forest Operations Map (DEV)");

  private static FamApplicationUserRoleAssignmentGetDto assignment(
      String userName, String firstName, String lastName, String email,
      String forestClientNumber, String roleDisplayName) {

    FamForestClientDto forestClient = forestClientNumber == null
        ? null
        : new FamForestClientDto(null, forestClientNumber, null);

    return new FamApplicationUserRoleAssignmentGetDto(
        1L, 2L, 3L,
        new FamUserInfoDto(userName, new FamUserTypeDto(UserType.IDIR, "IDIR"), firstName, lastName, email),
        new FamRoleWithClientDto(3L, "FOM_REVIEWER", "C", roleDisplayName, null,
            APPLICATION, forestClient, null),
        OffsetDateTime.of(2024, 3, 15, 10, 30, 0, 0, ZoneOffset.UTC),
        null);
  }

  @Test
  @DisplayName("writes the download date, application line, header and rows")
  void writesFullLayout() {
    String csv = exporter.toCsv(List.of(
        assignment("JSMITH", "Jane", "Smith", "jane@example.com", "00001011", "Reviewer")));

    String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
    assertThat(csv.lines().toList()).containsExactly(
        "Downloaded on: " + today,
        "Application: Forest Operations Map (DEV)",
        "User Name,Domain,First Name,Last Name,Email,Forest Client ID,Role,Added On",
        "JSMITH,IDIR,Jane,Smith,jane@example.com,'00001011',Reviewer,2024-03-15");
  }

  @Test
  @DisplayName("wraps the forest client number in apostrophes so Excel keeps leading zeros")
  void protectsForestClientNumberFromExcel() {
    String csv = exporter.toCsv(List.of(
        assignment("JSMITH", "Jane", "Smith", "j@e.com", "00001011", "Reviewer")));

    assertThat(csv).contains(",'00001011',");
  }

  @Test
  @DisplayName("leaves the forest client column empty for an unscoped role")
  void emptyForestClientColumnWhenUnscoped() {
    String csv = exporter.toCsv(List.of(
        assignment("JSMITH", "Jane", "Smith", "j@e.com", null, "Reviewer")));

    assertThat(csv.lines().toList().get(3)).isEqualTo("JSMITH,IDIR,Jane,Smith,j@e.com,,Reviewer,2024-03-15");
  }

  @Test
  @DisplayName("quotes values containing a comma")
  void quotesEmbeddedCommas() {
    String csv = exporter.toCsv(List.of(
        assignment("JSMITH", "Jane", "Smith", "j@e.com", null, "Reviewer, Senior")));

    assertThat(csv).contains("\"Reviewer, Senior\"");
  }

  @Test
  @DisplayName("doubles embedded quotes")
  void escapesEmbeddedQuotes() {
    String csv = exporter.toCsv(List.of(
        assignment("JSMITH", "Jane \"JJ\" Smith", "Smith", "j@e.com", null, "Reviewer")));

    assertThat(csv).contains("\"Jane \"\"JJ\"\" Smith\"");
  }

  @Test
  @DisplayName("emits only the download date when there is nothing to export")
  void emptyExportHasNoHeader() {
    String csv = exporter.toCsv(List.of());

    assertThat(csv.lines()).hasSize(1);
    assertThat(csv).startsWith("Downloaded on: ").doesNotContain("User Name");
  }

  @Test
  @DisplayName("names the file after the application and today's date")
  void filenameIncludesApplicationAndDate() {
    String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));

    assertThat(exporter.buildFilename(List.of(
        assignment("JSMITH", "Jane", "Smith", "j@e.com", null, "Reviewer"))))
        .isEqualTo("application_FOM_DEV_user_roles-" + today + ".csv");
  }

  @Test
  @DisplayName("falls back to a generic filename when there is nothing to export")
  void filenameFallsBackWhenEmpty() {
    assertThat(exporter.buildFilename(List.of())).isEqualTo("user_roles.csv");
  }
}
