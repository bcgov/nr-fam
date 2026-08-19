import { flushPromises, mount } from "@vue/test-utils";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { watch } from "vue";
import { authState } from "@/providers/authState";

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

vi.mock("@/services/keycloak", () => ({
    getUserManager: () => ({
        signinRedirect: vi.fn(),
        signoutRedirect: vi.fn(),
        signinRedirectCallback: vi.fn(),
    }),
    loadStoredUser: (...args: unknown[]) => loadStoredUser(...args),
    KC_IDP_HINT: "kc_idp_hint",
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
