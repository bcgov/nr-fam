package ca.bc.gov.nrs.fam.constants;

import java.util.Locale;
import java.util.Optional;

/**
 * The names of FAM's three administrative roles, and how to read them back.
 *
 * <p>These live on <b>FAM's own CSS integration</b>, not on the integration of
 * the application being administered. A token carries {@code client_roles} for
 * the client it was issued to, so a role sitting on another application's
 * integration would never reach FAM. That is why the application has to be named
 * inside the role rather than implied by where the role lives.
 *
 * <pre>
 * FAM_ADMIN                      administers everything
 * APP_ADMIN_&lt;integrationId&gt;_&lt;ENV&gt;        e.g. APP_ADMIN_22264_DEV
 * DELEGATED_ADMIN_&lt;integrationId&gt;_&lt;ENV&gt;  e.g. DELEGATED_ADMIN_22264_DEV
 * </pre>
 *
 * <p>The integration id is used rather than the project name because FAM already
 * holds both halves of it - a CSS application <em>is</em> an integration id and
 * an environment - and because renaming a project in CSS would otherwise silently
 * revoke everyone's access.
 *
 * <p>Environment is part of the name, so administering DEV does not imply
 * administering PROD.
 */
public final class FamAdminRole {

  /** Administers every application, in every environment. */
  public static final String FAM_ADMIN = "FAM_ADMIN";

  private static final String APP_ADMIN_PREFIX = "APP_ADMIN_";
  private static final String DELEGATED_ADMIN_PREFIX = "DELEGATED_ADMIN_";

  private FamAdminRole() {}

  /** {@code APP_ADMIN_22264_DEV} */
  public static String appAdmin(int cssIntegrationId, String cssEnvironment) {
    return APP_ADMIN_PREFIX + suffix(cssIntegrationId, cssEnvironment);
  }

  /** {@code DELEGATED_ADMIN_22264_DEV} */
  public static String delegatedAdmin(int cssIntegrationId, String cssEnvironment) {
    return DELEGATED_ADMIN_PREFIX + suffix(cssIntegrationId, cssEnvironment);
  }

  /**
   * Separates the application a delegation is for from the role it delegates.
   *
   * <p>Two underscores, because one is legal inside a role code. Splitting is
   * still unambiguous even when the delegated role contains {@code __} of its
   * own: the environment cannot contain an underscore, so the <em>first</em>
   * {@code __} after it is always the boundary.
   */
  private static final String DELEGATION_SEPARATOR = "__";

  /**
   * A delegation: the authority to grant one specific role.
   *
   * <pre>
   * DELEGATED_ADMIN_22264_DEV__FREP_EDITOR
   * DELEGATED_ADMIN_22264_DEV__FREP_EDITOR_DISTRICT-DCC
   * </pre>
   *
   * <p>Port of legacy's {@code fam_access_control_privilege(user_id, role_id)}.
   * A delegated administrator was never authorised over an application as a
   * whole - the privilege named a concrete role, and for a scoped role it named
   * the per-scope child, so "may grant Submitter for forest client 00001018" was
   * expressible and "may grant anything in FREP" was not.
   *
   * <p>Held on <b>FAM's own integration</b>, like the other administrative roles:
   * the application is named inside the role because a token only carries roles
   * of the client it was issued to.
   *
   * <p>Names stay well inside Keycloak's 255-character limit: the prefix,
   * integration id and environment cost about 26 characters, leaving the role
   * code (59 at most) and its scope suffix comfortable room.
   *
   * @param roleName the <em>concrete</em> CSS role, scope suffix included
   */
  public static String delegation(int cssIntegrationId, String cssEnvironment, String roleName) {
    return delegatedAdmin(cssIntegrationId, cssEnvironment) + DELEGATION_SEPARATOR + roleName;
  }

  /**
   * The role a delegation authorises, or empty if this is not a delegation.
   *
   * <p>Empty for the plain {@code DELEGATED_ADMIN_22264_DEV} tier marker, which
   * names no role - see {@link #isDelegation}.
   */
  public static Optional<String> delegatedRoleOf(String roleName) {
    if (roleName == null) {
      return Optional.empty();
    }
    String name = roleName.trim();
    if (!name.toUpperCase(Locale.ROOT).startsWith(DELEGATED_ADMIN_PREFIX)) {
      return Optional.empty();
    }
    int separator = name.indexOf(DELEGATION_SEPARATOR, DELEGATED_ADMIN_PREFIX.length());
    if (separator < 0 || separator == name.length() - DELEGATION_SEPARATOR.length()) {
      return Optional.empty();
    }
    return Optional.of(name.substring(separator + DELEGATION_SEPARATOR.length()));
  }

  /** Whether this role delegates one specific role rather than marking the tier. */
  public static boolean isDelegation(String roleName) {
    return delegatedRoleOf(roleName).isPresent();
  }

  private static String suffix(int cssIntegrationId, String cssEnvironment) {
    return cssIntegrationId + "_"
        + (cssEnvironment == null ? "" : cssEnvironment.toUpperCase(Locale.ROOT));
  }

  /**
   * The tier a role name grants, if it is one of FAM's administrative roles.
   *
   * <p>Empty for anything else - an application's own roles pass through here
   * too, and must not be mistaken for administrative authority.
   */
  public static Optional<AdminRoleAuthGroup> tierOf(String roleName) {
    if (roleName == null) {
      return Optional.empty();
    }
    String name = roleName.trim().toUpperCase(Locale.ROOT);

    if (FAM_ADMIN.equals(name)) {
      return Optional.of(AdminRoleAuthGroup.FAM_ADMIN);
    }
    // DELEGATED_ADMIN_ is checked first: APP_ADMIN_ is not a prefix of it, but
    // ordering the more specific test first keeps that independent of the names.
    if (name.startsWith(DELEGATED_ADMIN_PREFIX)) {
      return Optional.of(AdminRoleAuthGroup.DELEGATED_ADMIN);
    }
    if (name.startsWith(APP_ADMIN_PREFIX)) {
      return Optional.of(AdminRoleAuthGroup.APP_ADMIN);
    }
    return Optional.empty();
  }

  /** Whether a role name is one of FAM's administrative roles at all. */
  public static boolean isAdminRole(String roleName) {
    return tierOf(roleName).isPresent();
  }

  /** The application an administrative role names. */
  public record AdminRoleTarget(int cssIntegrationId, String cssEnvironment) {}

  /**
   * The application an {@code APP_ADMIN_} or {@code DELEGATED_ADMIN_} role names.
   *
   * <p>The inverse of {@link #appAdmin} and {@link #delegatedAdmin}, for reading
   * a caller's own roles back into applications they administer.
   *
   * <p>Empty for {@link #FAM_ADMIN}, which names no application because it
   * administers every one, and for anything that is not an administrative role.
   * Also empty for a malformed name: a role whose id will not parse names no
   * application, and inventing one from it would be worse than ignoring it.
   *
   * <p>Split at the <em>last</em> underscore, not the first: the id comes before
   * the environment and neither contains one, but an environment added later
   * with an underscore would break a first-underscore split silently.
   */
  public static Optional<AdminRoleTarget> targetOf(String roleName) {
    if (roleName == null) {
      return Optional.empty();
    }
    String name = roleName.trim().toUpperCase(Locale.ROOT);

    String suffix;
    if (name.startsWith(DELEGATED_ADMIN_PREFIX)) {
      suffix = name.substring(DELEGATED_ADMIN_PREFIX.length());
    } else if (name.startsWith(APP_ADMIN_PREFIX)) {
      suffix = name.substring(APP_ADMIN_PREFIX.length());
    } else {
      return Optional.empty();
    }

    // A delegation carries the role it delegates after the separator. Everything
    // below splits on the LAST underscore, which would otherwise read
    // DELEGATED_ADMIN_22264_DEV__FREP_EDITOR as integration "22264_DEV__FREP"
    // and fail to parse, silently dropping the row.
    int delegation = suffix.indexOf(DELEGATION_SEPARATOR);
    if (delegation >= 0) {
      suffix = suffix.substring(0, delegation);
    }

    int separator = suffix.lastIndexOf('_');
    if (separator <= 0 || separator == suffix.length() - 1) {
      return Optional.empty();
    }

    try {
      return Optional.of(new AdminRoleTarget(
          Integer.parseInt(suffix.substring(0, separator)),
          suffix.substring(separator + 1).toLowerCase(Locale.ROOT)));
    } catch (NumberFormatException e) {
      return Optional.empty();
    }
  }
}
