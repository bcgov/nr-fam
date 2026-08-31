package ca.bc.gov.nrs.fam.constants;

/**
 * Which instance of the identity directory to look a person up in.
 *
 * <p>Three, not the two {@link ApiInstanceEnv} offers, because BCeID is deployed
 * in three environments and a person is a <em>different account with a different
 * GUID</em> in each. That makes the directory instance part of the answer rather
 * than a deployment detail: a GUID read from the test directory does not exist in
 * production, and CSS refuses to assign a role to a username it cannot resolve
 * against the identity provider for the environment being written to.
 *
 * <p>So the instance follows the <b>application being administered</b>, which is
 * the same key that decides where the role itself is written - see
 * {@code ApiInstanceEnvResolver#resolveDirectory}. Looking somebody up in one
 * environment and assigning them in another is the failure this exists to
 * prevent, and it is silent: the appointment reports success and the person never
 * appears.
 */
public enum DirectoryEnv {
  DEV,
  TEST,
  PROD
}
