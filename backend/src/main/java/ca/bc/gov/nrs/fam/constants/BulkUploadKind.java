package ca.bc.gov.nrs.fam.constants;

/**
 * What a bulk upload file appoints or grants.
 *
 * <p>The three share a file shape and a validation pass and differ in what they
 * do with a validated row. Keeping them one service rather than three is
 * deliberate: the expensive, fiddly parts - parsing, one directory lookup per
 * person rather than per row, scope resolution, the per-row authorisation
 * checks that make the preview honest - are identical, and the differences are
 * small enough to name here.
 *
 * <p>There is no DevOps entry. A DevOps administrator is appointed for an
 * application rather than for access within one, the tier is small by nature,
 * and nothing in the migration produces a list of them to load.
 */
public enum BulkUploadKind {

  /**
   * Access within the application. Every column is used: the role, and whichever
   * scope columns that role is granted per.
   */
  USERS,

  /**
   * Who may grant a role, rather than who holds it. Same columns as {@link
   * #USERS} and read the same way - a delegation names exactly one concrete
   * role, so "Submitter for district X" is a row in both files and means
   * different things in each.
   */
  DELEGATED_ADMINS,

  /**
   * Application administrators. One column - the username, and nothing else.
   *
   * <p>The tier is the whole appointment, so there is no role to name and no
   * scope to carry. There is no user type either: the tier is IDIR-only, so the
   * column could only ever repeat what the file already means, and a file
   * carrying the wrong value in it would be asking for something FAM refuses.
   */
  APP_ADMINS;

  /** Whether a row of this kind names a role of the application. */
  public boolean needsRole() {
    return this != APP_ADMINS;
  }

  /** The columns a template for this kind should carry. */
  public String csvHeader() {
    return this == APP_ADMINS
        ? "username"
        : "username,user_type,role,district,organization,region";
  }
}
