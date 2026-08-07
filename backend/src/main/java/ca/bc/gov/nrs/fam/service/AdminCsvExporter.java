package ca.bc.gov.nrs.fam.service;

import ca.bc.gov.nrs.fam.dto.FamAccessControlPrivilegeGetResponse;
import ca.bc.gov.nrs.fam.dto.FamAppAdminGetResponse;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * CSV exports for the administrator screens.
 *
 * <p>Ports the two private exporters in {@code router_application_admin.py} and
 * {@code router_access_control_privilege.py}. Same layout as the end-user export
 * - download date, a title line, header row, data - and the same
 * apostrophe-wrapping so Excel does not eat leading zeros.
 */
@Component
public class AdminCsvExporter {

  private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

  private static final List<String> APP_ADMIN_COLUMNS = List.of(
      "User Name", "Domain", "First Name", "Last Name", "Email",
      "Application", "Environment", "Role Enable To Assign");

  private static final List<String> DELEGATED_ADMIN_COLUMNS = List.of(
      "User Name", "Domain", "First Name", "Last Name", "Email",
      "Forest Client ID", "Role Enable To Assign", "Added On");

  public String applicationAdminFilename() {
    return "FAM_app_admins-%s.csv".formatted(LocalDate.now().format(DATE));
  }

  public String delegatedAdminFilename(List<FamAccessControlPrivilegeGetResponse> data) {
    if (data.isEmpty()) {
      return "user_roles.csv";
    }
    return "application_%s_delegated_admin_roles-%s.csv".formatted(
        data.get(0).role().application().applicationName(), LocalDate.now().format(DATE));
  }

  public String toApplicationAdminCsv(List<FamAppAdminGetResponse> data) {
    List<Map<String, String>> rows = new ArrayList<>(data.size());
    for (FamAppAdminGetResponse item : data) {
      Map<String, String> row = new LinkedHashMap<>();
      row.put("User Name", item.user().userName());
      row.put("Domain", item.user().userType() == null ? null : item.user().userType().description());
      row.put("First Name", item.user().firstName());
      row.put("Last Name", item.user().lastName());
      row.put("Email", item.user().email());
      // Upstream wraps the description in apostrophes, as it does client numbers.
      row.put("Application", protect(item.application().applicationDescription()));
      row.put("Environment", item.application().appEnvironment() == null
          ? null
          : item.application().appEnvironment().getCode());
      // Application admins always assign the same thing.
      row.put("Role Enable To Assign", "Admin");
      rows.add(row);
    }

    return render("FAM Application Admin", APP_ADMIN_COLUMNS, rows);
  }

  public String toDelegatedAdminCsv(List<FamAccessControlPrivilegeGetResponse> data) {
    String titleLine = data.isEmpty()
        ? null
        : "Application: " + data.get(0).role().application().applicationDescription();

    List<Map<String, String>> rows = new ArrayList<>(data.size());
    for (FamAccessControlPrivilegeGetResponse item : data) {
      Map<String, String> row = new LinkedHashMap<>();
      row.put("User Name", item.user().userName());
      row.put("Domain", item.user().userType() == null ? null : item.user().userType().description());
      row.put("First Name", item.user().firstName());
      row.put("Last Name", item.user().lastName());
      row.put("Email", item.user().email());
      row.put("Forest Client ID", item.role().forestClient() == null
          ? null
          : protect(item.role().forestClient().forestClientNumber()));
      row.put("Role Enable To Assign", item.role().displayName());
      row.put("Added On", item.createDate() == null ? null : item.createDate().format(DATE));
      rows.add(row);
    }

    return render(titleLine, DELEGATED_ADMIN_COLUMNS, rows);
  }

  /** Excel strips leading zeros from anything numeric-looking; apostrophes stop it. */
  private static String protect(String value) {
    return value == null ? null : "'" + value + "'";
  }

  private static String render(
      String titleLine, List<String> columns, List<Map<String, String>> rows) {

    StringBuilder out = new StringBuilder();
    out.append("Downloaded on: ").append(LocalDate.now().format(DATE)).append('\n');

    if (rows.isEmpty()) {
      // Matches upstream: no rows means no title line and no header.
      return out.toString();
    }

    if (titleLine != null) {
      out.append(titleLine).append('\n');
    }

    out.append(String.join(",", columns)).append("\r\n");
    for (Map<String, String> row : rows) {
      out.append(columns.stream().map(c -> escape(row.get(c))).reduce((a, b) -> a + "," + b)
          .orElse("")).append("\r\n");
    }
    return out.toString();
  }

  /** Minimal quoting, matching Python's {@code csv.QUOTE_MINIMAL} default. */
  private static String escape(String value) {
    if (value == null) {
      return "";
    }
    boolean needsQuoting = value.contains(",") || value.contains("\"")
        || value.contains("\n") || value.contains("\r");
    return needsQuoting ? '"' + value.replace("\"", "\"\"") + '"' : value;
  }
}
