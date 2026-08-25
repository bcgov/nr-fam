import { describe, it, expect, beforeEach, afterEach } from "vitest";
import {
    getUserManager,
    loadStoredUser,
    resetUserManager,
    KC_IDP_HINT,
    AUTH_CALLBACK_PATH,
} from "@/services/keycloak";
import type { User } from "oidc-client-ts";

const ISSUER = "https://dev.loginproxy.gov.bc.ca/auth/realms/standard";
const CLIENT_ID = "fam-console-local";
const APP_ORIGIN = "http://localhost:3000";

const asEnvEntry = (value: string) => ({ sensitive: false, type: "string", value });

const setEnv = () =>
    window.localStorage.setItem(
        "env_data",
        JSON.stringify({
            keycloak_issuer_uri: asEnvEntry(ISSUER),
            keycloak_client_id: asEnvEntry(CLIENT_ID),
            front_end_redirect_base_url: asEnvEntry(APP_ORIGIN),
        })
    );

describe("keycloak OIDC client", () => {
    beforeEach(() => {
        window.localStorage.clear();
        resetUserManager();
    });
    afterEach(() => {
        window.localStorage.clear();
        resetUserManager();
    });

    it("configures the realm, client and callback from runtime env", () => {
        setEnv();

        const settings = getUserManager().settings;

        expect(settings.authority).toBe(ISSUER);
        expect(settings.client_id).toBe(CLIENT_ID);
        expect(settings.redirect_uri).toBe(`${APP_ORIGIN}${AUTH_CALLBACK_PATH}`);
        expect(settings.post_logout_redirect_uri).toBe(APP_ORIGIN);
    });

    it("uses authorization code flow, which means PKCE for a public browser client", () => {
        setEnv();

        expect(getUserManager().settings.response_type).toBe("code");
    });

    it("requests the scopes the backend reads identity from", () => {
        setEnv();

        // The backend needs email and the provider-specific profile claims.
        expect(getUserManager().settings.scope).toContain("openid");
        expect(getUserManager().settings.scope).toContain("profile");
        expect(getUserManager().settings.scope).toContain("email");
    });

    it("exposes the BC Gov SSO provider aliases used for kc_idp_hint", () => {
        // These must match the values the backend reads back out of the
        // identity_provider claim. IDIR federates to Azure AD under
        // `azureidir`; `idir` is not an alias this realm has, and Keycloak
        // silently ignores a hint it does not recognise.
        expect(KC_IDP_HINT.IDIR).toBe("azureidir");
        expect(KC_IDP_HINT.BCEIDBUSINESS).toBe("bceidbusiness");
    });

    it("gives the two providers different aliases", () => {
        // The failure that started this: both buttons landing on the Microsoft
        // sign-in page. Keycloak ignores a hint naming a provider the client
        // does not have and falls through to the one it does, so two aliases
        // that collide - or one that is wrong - look identical from the outside.
        expect(KC_IDP_HINT.IDIR).not.toBe(KC_IDP_HINT.BCEIDBUSINESS);
    });

    it("is created lazily, since env.json is fetched after module evaluation", () => {
        // No env in storage yet: constructing at import time would throw.
        expect(() => getUserManager()).toThrow();

        setEnv();
        expect(getUserManager()).toBeDefined();
    });

    it("reuses a single manager instance", () => {
        setEnv();

        expect(getUserManager()).toBe(getUserManager());
    });
});

/**
 * The landing page renders behind a spinner until session restore finishes, so
 * anything slow here is visible to every first-time visitor.
 */
describe("loadStoredUser", () => {
    const asUser = (expired: boolean) =>
        ({ expired, access_token: "tok" }) as unknown as User;

    it("does not attempt a silent renewal when nobody is signed in", async () => {
        // signinSilent has no refresh token to use here, so oidc-client-ts falls
        // back to a hidden-iframe flow. No silent_redirect_uri is configured, so
        // it cannot succeed - it just runs until its 10 second timeout. That is
        // ten seconds of spinner on the landing page for every new visitor.
        let silentCalls = 0;

        const user = await loadStoredUser({
            getUser: async () => null,
            signinSilent: async () => {
                silentCalls += 1;
                return asUser(false);
            },
        } as any);

        expect(user).toBeNull();
        expect(silentCalls).toBe(0);
    });

    it("returns a valid stored user without renewing", async () => {
        let silentCalls = 0;
        const stored = asUser(false);

        const user = await loadStoredUser({
            getUser: async () => stored,
            signinSilent: async () => {
                silentCalls += 1;
                return asUser(false);
            },
        } as any);

        expect(user).toBe(stored);
        expect(silentCalls).toBe(0);
    });

    it("renews silently when the stored token has expired", async () => {
        // The case signinSilent actually exists for: there is a refresh token.
        const renewed = asUser(false);
        let silentCalls = 0;

        const user = await loadStoredUser({
            getUser: async () => asUser(true),
            signinSilent: async () => {
                silentCalls += 1;
                return renewed;
            },
        } as any);

        expect(user).toBe(renewed);
        expect(silentCalls).toBe(1);
    });
});
