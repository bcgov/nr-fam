package ca.bc.gov.nrs.fam.constants;

/**
 * Which instance of an external API to call. The Forest Client API and the IDIM
 * proxy only publish TEST and PROD instances, so FAM's DEV environment talks to
 * their TEST instance.
 */
public enum ApiInstanceEnv {
  TEST,
  PROD
}
