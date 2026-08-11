package ca.bc.gov.nrs.fam.dto;

import ca.bc.gov.nrs.fam.constants.UserType;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Encoding and decoding of the information CSS roles cannot hold natively.
 *
 * <p>A CSS role is a bare name. It has no attributes, no description and no
 * display name, so anything FAM needs to know about a role has to be either
 * encoded into the name or expressed through composite membership.
 *
 * <p>Two conventions carry the load:
 *
 * <ul>
 *   <li><b>Scope type</b> - a marker role in the composite chain
 *       ({@link #MARKER_DISTRICT}, {@link #MARKER_FOREST_CLIENT}).
 *   <li><b>Scope value</b> - appended to the role name at grant time,
 *       {@code <role>_<SCOPE_TYPE>-<value>}, because the name is the only part
 *       of a role that reaches the token.
 * </ul>
 */
public final class CssRoleNaming {

  /** A role composed of this is scoped by natural resource district. */
  public static final String MARKER_DISTRICT = "HAS_DISTRICT_ROLE";

  /** A role composed of this is scoped by forest client. */
  public static final String MARKER_FOREST_CLIENT = "HAS_FOREST_CLIENT";

  public static final List<String> MARKERS = List.of(MARKER_DISTRICT, MARKER_FOREST_CLIENT);

  /**
   * Scope types that may appear in a generated role name.
   *
   * <p>Parsing matches against this list rather than splitting on the last
   * underscore. {@code FOREST_CLIENT} contains an underscore itself, so a blind
   * split reads {@code FOM_SUBMITTER_FOREST_CLIENT-00001018} as scope type
   * {@code CLIENT} of role {@code FOM_SUBMITTER_FOREST} - the value round-trips
   * but the role and scope type do not. Matching known types instead keeps
   * multi-word scope types intact, and leaves a role that merely happens to
   * contain a hyphen alone.
   *
   * <p>Longest first, so a future scope type that suffixes another still matches
   * the more specific one.
   */
  private static final List<String> SCOPE_TYPES = List.of("FOREST_CLIENT", "DISTRICT");

  private CssRoleNaming() {}

  /**
   * Name for the scope-specific role created on demand at grant time.
   *
   * <pre>CHR_FREP_EDITOR + DISTRICT + DCC -> CHR_FREP_EDITOR_DISTRICT-DCC</pre>
   */
  public static String buildScopedRoleName(String baseRoleName, String scopeType, String value) {
    return "%s_%s-%s".formatted(baseRoleName, scopeType, value);
  }

  /**
   * Split a generated role name back into base role, scope type and scope value.
   *
   * <p>The scope exists nowhere else, so this is the only way to recover it when
   * reading assignments back out of CSS.
   *
   * <pre>
   * CHR_FREP_EDITOR_DISTRICT-DCC            -> (CHR_FREP_EDITOR, DISTRICT, DCC)
   * FOM_SUBMITTER_FOREST_CLIENT-00001018    -> (FOM_SUBMITTER, FOREST_CLIENT, 00001018)
   * FREP_EDITOR                             -> (FREP_EDITOR, null, null)
   * SOME-ROLE                               -> (SOME-ROLE, null, null)
   * </pre>
   */
  public static ScopedRoleName parse(String roleName) {
    int hyphen = roleName.lastIndexOf('-');
    if (hyphen < 0) {
      return new ScopedRoleName(roleName, null, null);
    }

    String head = roleName.substring(0, hyphen);
    String value = roleName.substring(hyphen + 1);

    for (String scopeType : SCOPE_TYPES) {
      String suffix = "_" + scopeType;
      if (head.endsWith(suffix) && head.length() > suffix.length()) {
        return new ScopedRoleName(
            head.substring(0, head.length() - suffix.length()), scopeType, value);
      }
    }

    // A hyphen that is not a scope separator: the name is its own base role.
    return new ScopedRoleName(roleName, null, null);
  }

  /**
   * The CSS/Keycloak username for a FAM user.
   *
   * <p>Keycloak identifies federated users as {@code <guid>@<alias>} in lower
   * case. FAM stores GUIDs upper case, so they are lowered here.
   *
   * <p>The alias is supplied rather than assumed. The standard realm carries two
   * IDIR integrations - {@code azureidir} and the legacy {@code idir} - and CSS
   * offers no user search to check which one a person exists under. Assigning to
   * the wrong alias targets a username that does not exist, which CSS accepts
   * without complaint, so the grant silently does nothing.
   *
   * @param idpAlias the realm's suffix for this user's provider
   */
  public static String buildUsername(String userGuid, String idpAlias) {
    if (idpAlias == null || idpAlias.isBlank()) {
      throw new IllegalArgumentException("No CSS identity provider alias supplied.");
    }
    return "%s@%s".formatted(userGuid.toLowerCase(Locale.ROOT), idpAlias.toLowerCase(Locale.ROOT));
  }

  /** Resolves the configured alias for a user type, then builds the username. */
  public static String buildUsername(
      String userGuid, UserType userType, String idirAlias, String bceidAlias) {

    if (userType == null) {
      throw new IllegalArgumentException("No CSS identity provider mapping for a null user type.");
    }
    return switch (userType) {
      case IDIR -> buildUsername(userGuid, idirAlias);
      case BCEID -> buildUsername(userGuid, bceidAlias);
      // BC Services Card has no CSS provider. Inventing one would assign the
      // role to a username that cannot exist, and CSS would accept it.
      case BCSC_DEV, BCSC_TEST, BCSC_PROD -> throw new IllegalArgumentException(
          "No CSS identity provider mapping for user type " + userType.getCode());
    };
  }

  /**
   * {@code <guid>@idir -> IDIR}. Empty when the suffix is not one FAM recognises,
   * rather than guessing.
   */
  public static Optional<String> domainFromUsername(String username) {
    if (username == null) {
      return Optional.empty();
    }
    int at = username.indexOf('@');
    String idp = at < 0 ? "" : username.substring(at + 1).toLowerCase(Locale.ROOT);
    return Optional.ofNullable(switch (idp) {
      case "idir", "azureidir" -> "IDIR";
      case "bceidbusiness" -> "BCEID";
      default -> null;
    });
  }

  /** Result of {@link #parse}; scope fields are null for an unscoped role. */
  public record ScopedRoleName(String baseRoleName, String scopeType, String scopeValue) {}
}
