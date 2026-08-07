import { describe, it, expect, beforeEach, afterEach } from "vitest";
import {
    getUserManager,
    resetUserManager,
    KC_IDP_HINT,
    AUTH_CALLBACK_PATH,
} from "@/services/keycloak";

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
        // identity_provider claim.
        expect(KC_IDP_HINT.IDIR).toBe("idir");
        expect(KC_IDP_HINT.BCEIDBUSINESS).toBe("bceidbusiness");
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
