/**
 * BC Gov SSO (Keycloak) OIDC client.
 *
 * Replaces the Amplify/Cognito configuration in `amplifyconfiguration.ts`.
 * Configuration still comes from the runtime `env.json` loaded into
 * `localStorage` before the app mounts (see `public/env.js`), so a single built
 * image can be promoted between environments.
 *
 * Authorization Code + PKCE, tokens in memory + sessionStorage. There is no
 * client secret: this is a public browser client.
 */

import {
    UserManager,
    WebStorageStateStore,
    type User,
    type UserManagerSettings,
} from "oidc-client-ts";
import { requireEnvData } from "@/utils/EnvUtils";

/**
 * BC Gov SSO identity provider aliases, as configured on the realm. Passed as
 * `kc_idp_hint` so the user is sent straight to the right provider instead of
 * Keycloak's provider-selection screen.
 *
 * <b>These are sign-in hints, not the values that come back.</b> The standard
 * realm issues `azureidir` in the `identity_provider` claim for an IDIR sign-in,
 * so anything reading that claim has to accept `azureidir` as well as `idir` -
 * see the backend's `IdentityProvider` allowlist. Assuming the two sets were the
 * same is what made the profile pane show no directory for IDIR users.
 */
export const KC_IDP_HINT = {
    IDIR: "idir",
    BCEIDBUSINESS: "bceidbusiness",
} as const;

/** Path the realm redirects back to after a successful sign-in. */
export const AUTH_CALLBACK_PATH = "/authCallback";

let userManager: UserManager | null = null;

const buildSettings = (): UserManagerSettings => {
    const env = requireEnvData();

    const authority = env.keycloak_issuer_uri.value;
    const clientId = env.keycloak_client_id.value;
    const appOrigin = env.front_end_redirect_base_url.value;

    return {
        authority,
        client_id: clientId,
        redirect_uri: `${appOrigin}${AUTH_CALLBACK_PATH}`,
        post_logout_redirect_uri: appOrigin,
        response_type: "code",
        scope: "openid profile email",

        // Tokens survive a page reload but not a closed tab. localStorage would
        // leave them readable to any script on the origin for longer than the
        // session needs.
        userStore: new WebStorageStateStore({ store: window.sessionStorage }),
        stateStore: new WebStorageStateStore({ store: window.sessionStorage }),

        // The app refreshes on its own timer and enforces its own inactivity
        // timeout, matching the previous Amplify behaviour.
        automaticSilentRenew: false,

        // The callback route strips these from the URL itself.
        loadUserInfo: false,
    };
};

/**
 * The shared {@link UserManager}. Created on first use, because `env.json` is
 * fetched asynchronously and is not guaranteed to be in `localStorage` at module
 * evaluation time.
 */
export const getUserManager = (): UserManager => {
    if (!userManager) {
        userManager = new UserManager(buildSettings());
    }
    return userManager;
};

/** Only for tests, which build a fresh manager per case. */
export const resetUserManager = (): void => {
    userManager = null;
};

/**
 * The stored user, silently renewed if their access token has expired.
 *
 * Returns null when nobody is signed in, rather than attempting a renewal.
 *
 * That distinction matters more than it looks. `signinSilent` renews from the
 * stored refresh token - no iframe, unaffected by third-party cookie rules - but
 * only when there is a stored user to renew from. With nothing stored,
 * oidc-client-ts falls back to a hidden-iframe flow, and since no
 * `silent_redirect_uri` is configured that attempt cannot succeed: it runs until
 * `silentRequestTimeoutInSeconds` expires, which defaults to 10.
 *
 * Every first-time visitor to the landing page has nothing stored, so calling it
 * unconditionally held the page behind a loading spinner for ten seconds before
 * the sign-in buttons appeared.
 */
export const loadStoredUser = async (
    manager: Pick<UserManager, "getUser" | "signinSilent">
): Promise<User | null> => {
    const user = await manager.getUser();

    // Never signed in: there is no session to restore and nothing to renew.
    if (!user) {
        return null;
    }

    if (!user.expired) {
        return user;
    }

    return await manager.signinSilent();
};
