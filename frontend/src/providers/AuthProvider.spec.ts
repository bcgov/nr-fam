import { flushPromises, mount } from "@vue/test-utils";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { defineComponent, h, inject, watch } from "vue";
import { authState } from "@/providers/authState";
import { AUTH_KEY } from "@/constants/InjectionKeys";
import { IdpProvider } from "@/enum/IdpEnum";
import type { AuthContext } from "@/types/AuthTypes";

/**
 * Session restoration on a page reload.
 *
 * These exist because of a bug that made a refresh look like a permissions
 * problem: `isAuthRestored` was published as soon as the stored token was read,
 * before `/auth/self` had returned the roles. The route guards wait on exactly
 * that flag and then read `accessRoles`, so on a refresh a FAM administrator was
 * judged with an empty role list and redirected to `/no-access`.
 *
 * The invariant is an ordering one, so that is what is asserted: at the instant
 * the flag first turns true, the roles must already be there.
 */
const loadStoredUser = vi.fn();
const fetchSelf = vi.fn();
const signinRedirect = vi.fn();
const signoutRedirect = vi.fn();
const removeUser = vi.fn();

vi.mock("@/services/keycloak", () => ({
    getUserManager: () => ({
        signinRedirect: (...args: unknown[]) => signinRedirect(...args),
        signoutRedirect: (...args: unknown[]) => signoutRedirect(...args),
        removeUser: (...args: unknown[]) => removeUser(...args),
        signinRedirectCallback: vi.fn(),
    }),
    loadStoredUser: (...args: unknown[]) => loadStoredUser(...args),
    // The real aliases, not a placeholder. Stubbed as a bare string, both
    // `.IDIR` and `.BCEIDBUSINESS` read as undefined, and the suite stayed green
    // while the IDIR hint named a provider the realm does not have.
    KC_IDP_HINT: { IDIR: "azureidir", BCEIDBUSINESS: "bceidbusiness" },
    AUTH_CALLBACK_PATH: "/authCallback",
}));

vi.mock("@/services/AuthApiService", () => ({
    bootstrapLogin: vi.fn(() => Promise.resolve({ access_roles: [] })),
    fetchSelf: (...args: unknown[]) => fetchSelf(...args),
}));

vi.mock("vue-router", () => ({ useRouter: () => ({ push: vi.fn() }) }));

const USER = {
    access_token: "token",
    profile: { display_name: "Jane Smith", idir_username: "JSMITH" },
};

const mountProvider = async () => {
    const AuthProvider = (await import("./AuthProvider.vue")).default;
    const wrapper = mount(AuthProvider, {
        global: { stubs: { Spinner: true } },
        slots: { default: "<div />" },
    });
    for (let i = 0; i < 5; i++) {
        await flushPromises();
        await new Promise((resolve) => setTimeout(resolve, 0));
    }
    return wrapper;
};

describe("AuthProvider session restoration", () => {
    beforeEach(() => {
        authState.value = {
            isAuthenticated: false,
            famLoginUser: null,
            isAuthRestored: false,
            accessRoles: [],
        };
        signinRedirect.mockReset();
        loadStoredUser.mockReset().mockResolvedValue(USER);
        fetchSelf.mockReset().mockResolvedValue({ access_roles: ["FAM_ADMIN"] });
    });

    it("does not report the session restored until the roles have arrived", async () => {
        // What the guards see the moment they are released.
        let rolesWhenRestored: readonly string[] | null = null;
        watch(
            () => authState.value.isAuthRestored,
            (restored) => {
                if (restored && rolesWhenRestored === null) {
                    rolesWhenRestored = authState.value.accessRoles;
                }
            }
        );

        await mountProvider();

        expect(rolesWhenRestored).toEqual(["FAM_ADMIN"]);
    });

    it("restores the roles so an admin route is not refused after a refresh", async () => {
        await mountProvider();

        expect(authState.value.isAuthenticated).toBe(true);
        expect(authState.value.isAuthRestored).toBe(true);
        expect(authState.value.accessRoles).toEqual(["FAM_ADMIN"]);
    });

    it("still releases the guards when nobody is signed in", async () => {
        loadStoredUser.mockResolvedValue(null);

        await mountProvider();

        // Otherwise every guard would wait for a flag that never arrives.
        expect(authState.value.isAuthRestored).toBe(true);
        expect(authState.value.isAuthenticated).toBe(false);
    });

    it("still releases the guards when the roles call fails", async () => {
        fetchSelf.mockRejectedValue(new Error("self failed"));

        await mountProvider();

        expect(authState.value.isAuthRestored).toBe(true);
        expect(authState.value.isAuthenticated).toBe(false);
    });
});

/**
 * Which provider each button sends the browser to.
 *
 * Worth asserting because getting it wrong is silent: Keycloak ignores a
 * `kc_idp_hint` naming a provider the client does not have, and falls through to
 * whichever one it does. On a single-provider integration that means both
 * buttons reach the same sign-in page and only the wrong one looks broken.
 */
describe("AuthProvider sign-in", () => {
    beforeEach(() => {
        signinRedirect.mockReset();
        loadStoredUser.mockReset().mockResolvedValue(null);
        fetchSelf.mockReset().mockResolvedValue({ access_roles: [] });
    });

    /**
     * Drives login the way the landing page does - through the injected
     * context, from a child inside the provider's slot.
     */
    const hintFor = async (idp: IdpProvider) => {
        let auth: AuthContext | undefined;
        const Consumer = defineComponent({
            setup: () => {
                auth = inject<AuthContext>(AUTH_KEY);
                return () => h("div");
            },
        });

        const AuthProvider = (await import("./AuthProvider.vue")).default;
        mount(AuthProvider, {
            global: { stubs: { Spinner: true } },
            slots: { default: () => h(Consumer) },
        });
        await flushPromises();

        expect(auth, "AuthProvider did not provide an auth context").toBeTruthy();
        await auth!.login(idp as never);

        // The most recent call, not the first: a test that signs in twice would
        // otherwise read the same hint back both times and compare a value with
        // itself.
        return signinRedirect.mock.calls.at(-1)?.[0]?.extraQueryParams
            ?.kc_idp_hint;
    };

    it("sends IDIR to azureidir", async () => {
        // The realm federates IDIR to Azure AD under this alias. `idir` is not
        // one it has.
        expect(await hintFor(IdpProvider.IDIR)).toBe("azureidir");
    });

    it("sends Business BCeID to bceidbusiness", async () => {
        expect(await hintFor(IdpProvider.BCEIDBUSINESS)).toBe("bceidbusiness");
    });

    it("routes the two buttons to different providers", async () => {
        // This module is mocked here, so these assert the *routing* - that the
        // IDIR button reaches the IDIR constant rather than the other one. That
        // the constants hold the right aliases is keycloak.spec's job, against
        // the real module.
        expect(await hintFor(IdpProvider.IDIR)).not.toBe(
            await hintFor(IdpProvider.BCEIDBUSINESS)
        );
    });
});

/**
 * Signing out.
 *
 * The end-session request has to carry `id_token_hint`, or Keycloak does not
 * know which session to end - it answers with a confirmation page, or refuses,
 * and the realm session survives. oidc-client-ts reads that hint off the stored
 * user and removes the user itself, so anything here that clears storage first
 * breaks the logout while still looking like it worked: local state is gone, the
 * app returns to the landing page, and the next sign-in walks straight back in.
 */
describe("AuthProvider sign-out", () => {
    beforeEach(() => {
        signoutRedirect.mockReset();
        removeUser.mockReset();
        loadStoredUser.mockReset().mockResolvedValue(USER);
        fetchSelf.mockReset().mockResolvedValue({ access_roles: ["FAM_ADMIN"] });
    });

    const signOut = async () => {
        let auth: AuthContext | undefined;
        const Consumer = defineComponent({
            setup: () => {
                auth = inject<AuthContext>(AUTH_KEY);
                return () => h("div");
            },
        });

        const AuthProvider = (await import("./AuthProvider.vue")).default;
        mount(AuthProvider, {
            global: { stubs: { Spinner: true } },
            slots: { default: () => h(Consumer) },
        });
        await flushPromises();

        await auth!.logout();
    };

    it("redirects through the end-session endpoint", async () => {
        // Clearing local state alone would leave the realm session alive.
        await signOut();

        expect(signoutRedirect).toHaveBeenCalled();
    });

    it("leaves the stored user for the library to remove", async () => {
        // The hint comes off that user. Removing it here first would send a
        // logout Keycloak cannot attribute to a session.
        await signOut();

        expect(removeUser).not.toHaveBeenCalled();
    });

    it("clears the session it was holding", async () => {
        await signOut();

        expect(authState.value.isAuthenticated).toBe(false);
        expect(authState.value.accessRoles).toEqual([]);
    });
});
