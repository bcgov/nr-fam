/**
 * Federated logout-chain URL builder.
 *
 * This builds a multi-hop logout chain where each identity layer clears its own
 * session and then redirects to the next, ending back at the app:
 *
 *     app  →  Siteminder logoff.cgi  →  Keycloak end-session  →  app
 *
 * Without the chain, signing out of FAM alone leaves the Siteminder session
 * intact, so the next sign-in is silently resumed and the user cannot switch
 * accounts.
 *
 * Previously this had a fourth hop: Keycloak redirected to Cognito's `/logout`,
 * which then returned to the app. With Cognito gone, Keycloak returns to the app
 * directly - which means **the app origin must now be registered on the FAM
 * Keycloak client's "Valid post logout redirect URIs"**, per environment. Under
 * Cognito that allow-list held only the single, app-agnostic Cognito logout URL.
 *
 * Correctness hinges on encoding each nested URL EXACTLY ONCE with
 * `encodeURIComponent` as it is embedded in the outer layer's query string. This
 * preserves the structural `?` and `&` of an inner URL through the outer layer's
 * query parse. Without it, `logoff.cgi` peels an inner URL's `?...&...` off as
 * its own query params, silently dropping `post_logout_redirect_uri` at the
 * Siteminder hop — so Keycloak never learns where to go next and the chain breaks.
 */

import { getEnvValue } from "@/utils/EnvUtils";

/**
 * FAM authenticates through two Keycloak clients — one for IDIR, one for BCeID
 * Business. The chain MUST send the client id matching the IdP the user logged
 * in with.
 *
 * `idpProvider` comes from the token's `identity_provider` claim and is
 * `"idir"`, `"azureidir"` or `"bceidbusiness"`.
 */
const keycloakClientIdEnvKeyFor = (
    idpProvider: string | undefined
): string | null => {
    switch (idpProvider?.toLowerCase()) {
        case "idir":
        case "azureidir":
            return "logout_keycloak_client_id_idir";
        case "bceidbusiness":
            return "logout_keycloak_client_id_bceidbusiness";
        default:
            return null;
    }
};

/**
 * Builds the full federated logout-chain URL, nesting innermost → outermost so
 * each layer embeds the one below it.
 *
 * @param appReturnUrl The app origin the browser should land on once the chain
 *   completes. Must be registered as a valid post-logout redirect URI on the
 *   Keycloak client.
 * @param idpProvider The IdP the user logged in with — selects the matching
 *   Keycloak client id.
 * @returns The Siteminder logoff URL that drives the whole chain, or `null` if
 *   any required piece of config is missing, in which case the caller falls back
 *   to a plain Keycloak end-session redirect.
 */
export const buildFederatedLogoutUrl = (
    appReturnUrl: string,
    idpProvider: string | undefined
): string | null => {
    const keycloakClientIdKey = keycloakClientIdEnvKeyFor(idpProvider);
    const siteminderBase = getEnvValue("logout_siteminder_url");
    const keycloakBase = getEnvValue("logout_keycloak_url");
    const keycloakClientId = keycloakClientIdKey
        ? getEnvValue(keycloakClientIdKey)
        : undefined;
    const appOrigin = appReturnUrl?.trim();

    if (!siteminderBase || !keycloakBase || !keycloakClientId || !appOrigin) {
        return null; // → caller falls back to a plain Keycloak sign-out
    }

    // Innermost: Keycloak clears its session, then returns to the app.
    const keycloakLogout =
        `${keycloakBase}?client_id=${encodeURIComponent(keycloakClientId)}` +
        `&post_logout_redirect_uri=${encodeURIComponent(appOrigin)}`;

    // Outermost: Siteminder logs off the IDIR / BCeID session, then returns to
    // the Keycloak end-session URL. `retnow=1` forces an immediate return with
    // no interstitial.
    return `${siteminderBase}?retnow=1&returl=${encodeURIComponent(keycloakLogout)}`;
};
