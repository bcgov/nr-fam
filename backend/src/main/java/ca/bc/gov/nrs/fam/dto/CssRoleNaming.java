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
 * <p>Three conventions carry the load:
 *
 * <ul>
 *   <li><b>Scope type</b> - a marker role in the composite chain
 *       ({@link #MARKER_DISTRICT}, {@link #MARKER_FOREST_CLIENT}).
 *   <li><b>Scope value</b> - appended to the role name at grant time,
 *       {@code <role>_<SCOPE_TYPE>-<value>}, because the name is the only part
 *       of a role that reaches the token.
 *   <li><b>Description</b> - a sidecar role that exists only to hold text,
 *       {@link #LABEL_PREFIX}. See {@link #buildLabelRoleName}.
 * </ul>
 *
 * <p>That CSS holds nothing but a name is not an assumption - posting a role with
 * a {@code description} is refused outright with {@code "only name is
 * supported"}.
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

  /**
   * Marks a role as a description carrier rather than something grantable.
   *
   * <p>{@code FAM:LABEL:<CODE>:<free text>}.
   *
   * <p>The description is held on a role of its own rather than as a composite
   * child of the role it describes, because <b>composite children propagate into
   * the access token</b>. A child would put "FREP Administrator" into every
   * holder's token as though it were a role, leaving every downstream application
   * to filter it out. A sidecar is assigned to nobody and composed into nothing,
   * so it is visible only where FAM reads it: the role listing.
   *
   * <p>It also keeps the code and the description independent. Correcting a
   * description rewrites one sidecar; it does not touch the role that people hold,
   * so no assignment is disturbed. The alternative convention - naming the outer
   * composite role for the description, which is how the roles inherited from FSP
   * are shaped - means the display text is what gets assigned and scope-suffixed,
   * so a token carries {@code Submitter (SLR)_DISTRICT-DCC} and a reworded
   * description orphans every existing assignment.
   */
  public static final String LABEL_PREFIX = "FAM:LABEL:";

  /**
   * Prefix of the sidecar carrying a role's long description.
   *
   * <p>{@code FAM:DESC:<CODE>:<free text>}.
   *
   * <p>A second sidecar rather than a third field on the first one. Two reasons:
   * a role name is finite (Keycloak allows 255 characters) and a sentence plus a
   * short name plus a code would not reliably fit in one; and splitting a
   * three-part name is ambiguous, because both the name and the description may
   * contain colons.
   *
   * <p><b>Older roles have only a {@link #LABEL_PREFIX} sidecar, and that is
   * correct.</b> Its text has always been the short display name - "Submitter
   * (CHR)", "FREP Administrator" - so nothing needs rewriting: those roles simply
   * have no long description until somebody gives them one.
   */
  public static final String DESCRIPTION_PREFIX = "FAM:DESC:";

  /**
   * A role code FAM will create: upper case, starting with a letter.
   *
   * <p>Deliberately narrower than what CSS accepts (which is close to anything,
   * spaces included). The code reaches the token and is what applications
   * authorise on, and it is the left-hand side of both the scope suffix and the
   * sidecar, so it must not contain a delimiter. Excluding {@code :} and {@code -}
   * is what makes both parseable without ambiguity.
   */
  private static final java.util.regex.Pattern ROLE_CODE =
      java.util.regex.Pattern.compile("^[A-Z][A-Z0-9_]{1,58}$");

  private CssRoleNaming() {}

  /** Whether a code is one FAM is willing to create. */
  public static boolean isValidRoleCode(String roleCode) {
    return roleCode != null && ROLE_CODE.matcher(roleCode).matches();
  }

  /**
   * Name of the sidecar role holding a role's short display name.
   *
   * <pre>FSPTS_VIEW_ALL + "View All"
   *   -> FAM:LABEL:FSPTS_VIEW_ALL:View All</pre>
   */
  public static String buildLabelRoleName(String roleCode, String displayName) {
    return "%s%s:%s".formatted(LABEL_PREFIX, roleCode, displayName);
  }

  /**
   * Name of the sidecar role holding a role's long description.
   *
   * <pre>FSPTS_VIEW_ALL + "Allows users to view all the FSPs but not edit"
   *   -> FAM:DESC:FSPTS_VIEW_ALL:Allows users to view all the FSPs but not edit</pre>
   */
  public static String buildDescriptionRoleName(String roleCode, String description) {
    return "%s%s:%s".formatted(DESCRIPTION_PREFIX, roleCode, description);
  }

  /** Whether this role carries a display name rather than being grantable. */
  public static boolean isLabelRole(String roleName) {
    return roleName != null && roleName.startsWith(LABEL_PREFIX);
  }

  /** Whether this role carries a description rather than being grantable. */
  public static boolean isDescriptionRole(String roleName) {
    return roleName != null && roleName.startsWith(DESCRIPTION_PREFIX);
  }

  /**
   * Whether this role is one of FAM's sidecars, of either kind.
   *
   * <p>What every "is this something a person can hold" check wants: a sidecar is
   * assigned to nobody and must never appear as a grantable role, a table row or
   * a permission somebody holds.
   */
  public static boolean isSidecarRole(String roleName) {
    return isLabelRole(roleName) || isDescriptionRole(roleName);
  }

  /**
   * Read a display-name sidecar back into the code it names and its text.
   *
   * <p>Empty for any name that is not a well-formed sidecar, so a hand-made role
   * that merely starts with the prefix is ignored rather than half-read.
   */
  public static Optional<RoleLabel> parseLabel(String roleName) {
    return parseSidecar(roleName, LABEL_PREFIX);
  }

  /** Read a description sidecar back into the code it describes and its text. */
  public static Optional<RoleLabel> parseDescription(String roleName) {
    return parseSidecar(roleName, DESCRIPTION_PREFIX);
  }

  /**
   * Splits on the first colon after the prefix only: a code cannot contain one
   * (see {@link #isValidRoleCode}) but the text may, and truncating somebody's
   * description at a colon would be worse than keeping it whole.
   */
  private static Optional<RoleLabel> parseSidecar(String roleName, String prefix) {
    if (roleName == null || !roleName.startsWith(prefix)) {
      return Optional.empty();
    }
    String rest = roleName.substring(prefix.length());
    int separator = rest.indexOf(':');
    if (separator <= 0 || separator == rest.length() - 1) {
      return Optional.empty();
    }
    return Optional.of(new RoleLabel(rest.substring(0, separator), rest.substring(separator + 1)));
  }

  /** The marker role expressing a scope type, or empty when there is no scope. */
  public static Optional<String> markerFor(String scopeType) {
    if (scopeType == null || scopeType.isBlank()) {
      return Optional.empty();
    }
    return Optional.ofNullable(switch (scopeType.toUpperCase(Locale.ROOT)) {
      case "DISTRICT" -> MARKER_DISTRICT;
      case "FOREST_CLIENT" -> MARKER_FOREST_CLIENT;
      default -> null;
    });
  }

  /** A sidecar's text and the role code it belongs to. */
  public record RoleLabel(String roleCode, String text) {}

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
   * offers no user search to check which one a person exists under.
   *
   * <p>The wrong alias no longer fails silently: assignment goes through
   * {@code roles-new}, which verifies the username against the upstream identity
   * provider and refuses one it cannot resolve. {@code idir} in particular is
   * rejected outright as an unsupported provider. Getting it wrong is now a
   * visible error rather than a grant that appears to work and does nothing.
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
    };
  }

  /**
   * {@code <guid>@azureidir -> <GUID>}.
   *
   * <p>Upper cased: CSS reports the username in lower case while FAM and the
   * directory hold GUIDs upper case, and normalising is what lets callers use it
   * as a map key without resolving the same user twice.
   *
   * <p>Empty when the username is not in that form, which is the case for anyone
   * CSS could report by name instead.
   */
  public static Optional<String> guidFromUsername(String username) {
    if (username == null) {
      return Optional.empty();
    }
    int at = username.indexOf('@');
    if (at <= 0) {
      return Optional.empty();
    }
    String guid = username.substring(0, at);
    return guid.isBlank() ? Optional.empty() : Optional.of(guid.toUpperCase(Locale.ROOT));
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
