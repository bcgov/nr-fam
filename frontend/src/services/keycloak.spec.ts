import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";
import {
    ensureFreshToken,
    forceRenew,
    getUserManager,
    loadStoredUser,
    needsRenewal,
    RENEW_WHEN_SECONDS_LEFT,
    resetUserManager,
    KC_IDP_HINT,
    AUTH_CALLBACK_PATH,
} from "@/services/keycloak";
import type { User } from "oidc-client-ts";

const ISSUER = "https://dev.loginproxy.gov.bc.ca/auth/realms/standard";
const CLIENT_ID = "fam-console-local";
/* Whatever address the browser is on - jsdom's, here. Not a configured value:
   the client derives it from `window.location`, which is the point of the
   third test below. */
const APP_ORIGIN = window.location.origin;

const asEnvEntry = (value: string) => ({ sensitive: false, type: "string", value });

const setEnv = () =>
    window.localStorage.setItem(
        "env_data",
        JSON.stringify({
            keycloak_issuer_uri: asEnvEntry(ISSUER),
            keycloak_client_id: asEnvEntry(CLIENT_ID),
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

    it("returns to the address the browser is on, not one carried in env.json", () => {
        /*
            The vanity-DNS case. A deployment answers on more than one name -
            its route hostname and the friendly one in front of it - and only
            the browser knows which was used. A configured origin is right for
            at most one of them, and sending the wrong one is a `redirect_uri`
            the realm refuses because it is not where the request came from.

            env.json here still carries the stale route hostname, as a deployed
            ConfigMap written before this change would. It must not win.
        */
        const routeHostname = "https://nr-fam-prod.apps.gold.devops.gov.bc.ca";
        window.localStorage.setItem(
            "env_data",
            JSON.stringify({
                keycloak_issuer_uri: asEnvEntry(ISSUER),
                keycloak_client_id: asEnvEntry(CLIENT_ID),
                front_end_redirect_base_url: asEnvEntry(routeHostname),
            })
        );

        const settings = getUserManager().settings;

        expect(settings.redirect_uri).toBe(
            `${window.location.origin}${AUTH_CALLBACK_PATH}`
        );
        expect(settings.redirect_uri).not.toContain(routeHostname);
        expect(settings.post_logout_redirect_uri).not.toContain(routeHostname);
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

/**
 * Renewing before expiry rather than after it.
 *
 * This realm issues a five-minute access token. The app used to poll every three
 * minutes and renew only once the token had <em>already</em> expired, which left
 * up to three minutes in every five where every request carried a token the
 * backend refused - and no amount of clicking fixed it, because clicking is not
 * what drove the renewal.
 */
describe("token freshness", () => {
    const user = (expiresIn: number, expired = false) =>
        ({ expires_in: expiresIn, expired }) as unknown as User;

    describe("needsRenewal", () => {
        it("is true for a token that has already gone", () => {
            expect(needsRenewal(user(0, true))).toBe(true);
        });

        it("is true inside the skew window, before it expires", () => {
            expect(needsRenewal(user(RENEW_WHEN_SECONDS_LEFT - 1))).toBe(true);
        });

        it("is false while there is comfortable life left", () => {
            expect(needsRenewal(user(RENEW_WHEN_SECONDS_LEFT + 60))).toBe(false);
        });

        it("trusts an explicit not-expired when the seconds are not to hand", () => {
            // The two fields are not independent - oidc-client-ts derives
            // `expired` from `expires_in` - so `false` here already means there
            // is life left, and renewing anyway would rotate for nothing.
            expect(needsRenewal({ expired: false } as unknown as User)).toBe(false);
        });

        it("renews when neither field says anything", () => {
            // An unknown expiry is the case where being wrong costs a refused
            // request, so this guesses in the direction that recovers.
            expect(needsRenewal({} as unknown as User)).toBe(true);
        });
    });

    describe("ensureFreshToken", () => {
        it("does nothing when nobody is signed in", async () => {
            const signinSilent = vi.fn();
            const result = await ensureFreshToken({
                getUser: async () => null,
                signinSilent,
            } as never);

            expect(result).toBeNull();
            expect(signinSilent).not.toHaveBeenCalled();
        });

        it("leaves a healthy token alone, so it is cheap to call often", async () => {
            const signinSilent = vi.fn();
            const healthy = user(240);

            const result = await ensureFreshToken({
                getUser: async () => healthy,
                signinSilent,
            } as never);

            expect(result).toBe(healthy);
            expect(signinSilent).not.toHaveBeenCalled();
        });

        it("renews a token that is near expiry but not yet expired", async () => {
            const renewed = user(300);
            const signinSilent = vi.fn(async () => renewed);

            const result = await ensureFreshToken({
                getUser: async () => user(30),
                signinSilent,
            } as never);

            expect(signinSilent).toHaveBeenCalled();
            expect(result).toBe(renewed);
        });
    });

    describe("forceRenew", () => {
        it("renews however much life is left", async () => {
            // "Stay logged in" is not about the access token. Using the refresh
            // token rotates it, and that is what moves the thirty-minute
            // ceiling the dialog is counting down to.
            const signinSilent = vi.fn(async () => user(300));
            await forceRenew({ signinSilent } as never);
            expect(signinSilent).toHaveBeenCalled();
        });
    });
});

