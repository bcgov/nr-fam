package ca.bc.gov.nrs.fam.service;

import ca.bc.gov.nrs.fam.dto.FamApplicationUserRoleAssignmentGetDto;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Renders the application user-role export.
 *
 * <p>Port of {@code router_application.__export_app_user_roles_csv_file} and
 * {@code router_utils.csv_file_data_streamer}. The layout is two preamble lines
 * (download date, application), then a header row, then the data - and it is
 * consumed by people in Excel, so the shape is deliberately preserved.
 */
@Component
public class UserRoleCsvExporter {

  private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

  private static final List<String> COLUMNS = List.of(
      "User Name", "Domain", "First Name", "Last Name", "Email",
      "Forest Client ID", "Role", "Added On");

  /**
   * Excel strips leading zeros from anything that looks numeric, which corrupts
   * forest client numbers. Upstream wrapped the value in literal apostrophes to
   * prevent that; the same is done here.
   */
  private static String protectFromExcel(String forestClientNumber) {
    return forestClientNumber == null ? null : "'" + forestClientNumber + "'";
  }

  public String buildFilename(List<FamApplicationUserRoleAssignmentGetDto> data) {
    if (data.isEmpty()) {
      return "user_roles.csv";
    }
    String applicationName = data.get(0).role().application().applicationName();
    return "application_%s_user_roles-%s.csv"
        .formatted(applicationName, LocalDate.now().format(DATE));
  }

  public String toCsv(List<FamApplicationUserRoleAssignmentGetDto> data) {
    StringBuilder out = new StringBuilder();
    out.append("Downloaded on: ").append(LocalDate.now().format(DATE)).append('\n');

    if (data.isEmpty()) {
      // Matches upstream: with no rows there is no application line and no
      // header, only the download date.
      return out.toString();
    }

    out.append("Application: ")
        .append(data.get(0).role().application().applicationDescription())
        .append('\n');

    out.append(String.join(",", COLUMNS)).append("\r\n");
    for (Map<String, String> row : toRows(data)) {
      List<String> cells = COLUMNS.stream().map(c -> escape(row.get(c))).toList();
      out.append(String.join(",", cells)).append("\r\n");
    }
    return out.toString();
  }

  private List<Map<String, String>> toRows(List<FamApplicationUserRoleAssignmentGetDto> data) {
    List<Map<String, String>> rows = new ArrayList<>(data.size());
    for (FamApplicationUserRoleAssignmentGetDto item : data) {
      Map<String, String> row = new LinkedHashMap<>();
      row.put("User Name", item.user().userName());
      row.put("Domain", item.user().userType() == null ? null : item.user().userType().description());
      row.put("First Name", item.user().firstName());
      row.put("Last Name", item.user().lastName());
      row.put("Email", item.user().email());
      row.put("Forest Client ID", item.role().forestClient() == null
          ? null
          : protectFromExcel(item.role().forestClient().forestClientNumber()));
      row.put("Role", item.role().displayName());
      row.put("Added On", item.createDate() == null
          ? null
          : item.createDate().format(DATE));
      rows.add(row);
    }
    return rows;
  }

  /** Minimal quoting, matching Python's {@code csv.QUOTE_MINIMAL} default. */
  private static String escape(String value) {
    if (value == null) {
      return "";
    }
    boolean needsQuoting = value.contains(",") || value.contains("\"")
        || value.contains("\n") || value.contains("\r");
    if (!needsQuoting) {
      return value;
    }
    return '"' + value.replace("\"", "\"\"") + '"';
  }
}
