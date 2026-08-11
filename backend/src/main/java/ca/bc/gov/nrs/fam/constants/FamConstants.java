package ca.bc.gov.nrs.fam.constants;

/**
 * Ported from {@code server/backend/api/app/constants.py} (schema constants
 * section). Field-length constants mirror the column widths in
 * {@code migrations/sql} and are used by bean validation on the DTOs.
 */
public final class FamConstants {

  private FamConstants() {}

  public static final String SYSTEM_ACCOUNT_NAME = "system";
  public static final String FAM_PROXY_API_USER = "fam_proxy_api";

  /** FAM's own row in {@code app_fam.fam_application}. */
  public static final String APPLICATION_FAM = "FAM";

  public static final int USER_NAME_MAX_LEN = 20;
  public static final int FIRST_NAME_MAX_LEN = 50;
  public static final int LAST_NAME_MAX_LEN = 50;
  public static final int EMAIL_MAX_LEN = 250;
  public static final int CLIENT_NUMBER_MAX_LEN = 8;
  public static final int CLIENT_NAME_MAX_LEN = 60;
  public static final int ROLE_NAME_MAX_LEN = 100;
  public static final int APPLICATION_NAME_MAX_LEN = 100;
  public static final int APPLICATION_DESC_MAX_LEN = 200;
  public static final int CREATE_USER_MAX_LEN = 100;

  public static final int MIN_PAGE = 1;
  public static final int DEFAULT_PAGE_SIZE = 50;
  public static final int MIN_PAGE_SIZE = 10;
  /**
   * Deliberately large. Upstream comment: the intent is 100 per page, but the
   * frontend paginates client-side, so the API must be able to return everything.
   */
  public static final int MAX_PAGE_SIZE = 100_000;

  public static final int MAX_NUM_USERS_ASSIGNMENT_GRANT = 50;
  public static final int SEARCH_FIELD_MIN_LENGTH = 3;
  public static final int SEARCH_FIELD_MAX_LENGTH = 30;

  /** Forest Client API paginates from 0. */
  public static final int DEFAULT_FC_API_SEARCH_PAGE = 0;
  public static final int DEFAULT_FC_API_SEARCH_PAGE_SIZE = 50;

  public static final String DESCRIPTION_ACTIVE = "Active";
  public static final String DESCRIPTION_INACTIVE = "Inactive";

  /** Keys/values FAM uses to read the Forest Client API response. */
  public static final String FOREST_CLIENT_STATUS_KEY = "clientStatusCode";
  public static final String FOREST_CLIENT_STATUS_CODE_ACTIVE = "ACT";

  /**
   * Must stay in sync with the version rendered by the frontend's
   * TermsAndConditions component.
   */
  public static final String CURRENT_TERMS_AND_CONDITIONS_VERSION = "1";

  /** External API ({@code /external/v1}) pagination is 1-indexed. */
  public static final int EXT_MIN_PAGE = 1;
  public static final int EXT_DEFAULT_PAGE_SIZE = 50;
  public static final int EXT_MIN_PAGE_SIZE = 10;
  public static final int EXT_MAX_PAGE_SIZE = 100;
  /** Maximum the IDIM web service will accept. */
  public static final int EXT_IDIM_SEARCH_MAX_PAGE_SIZE = 500;
  public static final int EXT_MAX_IDP_USERNAME_LEN = 20;
  public static final int EXT_MAX_FIRST_NAME_LEN = 50;
  public static final int EXT_MAX_LAST_NAME_LEN = 50;
  public static final int EXT_MAX_ROLE_LEN = 25;
  public static final int EXT_MAX_ROLE_LIST_LEN = 5;
  public static final int EXT_APPLICATION_NAME_MAX_LEN = 25;
  public static final int EXT_ROLE_DISPLAY_NAME_MAX_LEN = 100;
}
