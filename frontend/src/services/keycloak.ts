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
 * <b>IDIR is `azureidir`, not `idir`.</b> The standard realm federates IDIR to
 * Azure AD under that alias, and it is what comes back in the
 * `identity_provider` claim - the same value `CSS_IDP_ALIAS_IDIR` defaults to
 * and the backend's `IdentityProvider` reads.
 *
 * A hint the realm does not recognise is not an error: Keycloak ignores it and
 * falls through to whichever provider the client has. On an integration with one
 * provider that means every button reaches the same place, so `idir` here sent
 * Business BCeID users to the Microsoft sign-in page and looked, from the IDIR
 * button, as though nothing were wrong.
 */
export const KC_IDP_HINT = {
    IDIR: "azureidir",
    BCEIDBUSINESS: "bceidbusiness",
} as const;

/** Path the realm redirects back to after a successful sign-in. */
export const AUTH_CALLBACK_PATH = "/authCallback";

let userManager: UserManager | null = null;

const buildSettings = (): UserManagerSettings => {
    const env = requireEnvData();

    const authority = env.keycloak_issuer_uri.value;
    const clientId = env.keycloak_client_id.value;
    /*
        The origin the browser is actually on, rather than one baked in at
        deploy time.

        This used to read `front_end_redirect_base_url` out of env.json, which
        the ConfigMap fills in as the OpenShift route hostname. That holds only
        while the app answers on exactly one name. It stops holding the moment
        a second one points at the same Route: reaching PROD through its vanity
        DNS still sent the route hostname as `redirect_uri`, so the realm was
        handed an address the request had not come from and refused it.

        Off `window.location` the callback returns to wherever the person
        started - vanity DNS, route hostname, a per-PR deploy, or localhost -
        and no environment has to be told its own address.

        It is not a way to redirect somewhere else. The realm only honours a
        `redirect_uri` already registered against the integration, so an
        unregistered origin is refused there; this decides which of the
        registered names to come back to, not whether to.
    */
    const appOrigin = window.location.origin;

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

    if (!needsRenewal(user)) {
        return user;
    }

    return await manager.signinSilent();
};

/**
 * How close to expiry a token may get before it is renewed.
 *
 * Renewing only once a token has <em>already</em> expired leaves a window where
 * every request carries a token the backend refuses. The access token lives five
 * minutes on this realm, so a minute of headroom is a fifth of its life - enough
 * to cover a slow renewal and clock skew between the browser and Keycloak,
 * without renewing so eagerly that the refresh token rotates for no reason.
 */
export const RENEW_WHEN_SECONDS_LEFT = 60;

/**
 * Whether this token is expired, or close enough that it soon will be.
 *
 * The two fields are not independent: oidc-client-ts derives `expired` from
 * `expires_in`, so an explicit `false` already means there is life left even
 * when the remaining seconds are not to hand. Only when neither says anything is
 * renewing the safer guess - an unknown expiry is exactly the case where being
 * wrong costs a refused request.
 */
export const needsRenewal = (user: Pick<User, "expired" | "expires_in">) => {
    const secondsLeft = user.expires_in;
    if (secondsLeft === undefined) {
        return user.expired !== false;
    }
    return secondsLeft <= RENEW_WHEN_SECONDS_LEFT;
};

/**
 * The stored user, renewed if the access token is at or near expiry.
 *
 * Cheap to call often - it is a no-op until the token is nearly out - which is
 * what lets the idle guard run it on user activity. That matters because the app
 * makes no request while somebody is reading a screen: without it, a person who
 * is plainly still there can return from five quiet minutes to a dead token.
 */
export const ensureFreshToken = async (
    manager: Pick<UserManager, "getUser" | "signinSilent">
): Promise<User | null> => {
    const user = await manager.getUser();
    if (!user) {
        return null;
    }
    return needsRenewal(user) ? await manager.signinSilent() : user;
};

/**
 * Renews regardless of how much life the access token has left.
 *
 * For "Stay logged in", where the point is not the access token but the refresh
 * token behind it: using it rotates it, which is what actually moves the
 * thirty-minute ceiling and buys the extra time the button promises.
 */
export const forceRenew = async (
    manager: Pick<UserManager, "signinSilent">
): Promise<User | null> => await manager.signinSilent();
