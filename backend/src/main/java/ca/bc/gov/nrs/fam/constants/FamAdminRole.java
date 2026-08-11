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
}
