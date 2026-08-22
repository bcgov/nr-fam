import { VueQueryPlugin } from "@tanstack/vue-query";
import { flushPromises, mount } from "@vue/test-utils";
import { beforeEach, describe, expect, it, vi } from "vitest";

/**
 * My permissions.
 *
 * The screen reports what the caller may administer, derived from the
 * administrative roles on their token. Two things are worth pinning: that a
 * caller who administers nothing is told so rather than shown an error, and that
 * FAM_ADMIN - which names no application or environment - reads sensibly rather
 * than as a blank row.
 */
const fetchSelfPermissions = vi.fn();
const fetchSelfApplicationRoles = vi.fn();

vi.mock("@/services/AuthApiService", () => ({
    fetchSelfPermissions: (...args: unknown[]) => fetchSelfPermissions(...args),
    fetchSelfApplicationRoles: (...args: unknown[]) =>
        fetchSelfApplicationRoles(...args),
    fetchSelf: vi.fn(),
    bootstrapLogin: vi.fn(),
}));

const settle = async () => {
    for (let i = 0; i < 5; i++) {
        await flushPromises();
        await new Promise((resolve) => setTimeout(resolve, 0));
    }
};

const mountView = async () => {
    const MyPermissions = (await import("./index.vue")).default;
    const wrapper = mount(MyPermissions, {
        global: {
            plugins: [
                [
                    VueQueryPlugin,
                    {
                        // Off for the tests only: the app retries a failed load,
                        // which is right in a browser but here just means the
                        // error state never settles.
                        queryClientConfig: {
                            defaultOptions: { queries: { retry: false } },
                        },
                    },
                ],
            ],
            stubs: {
                PageTitle: true,
                StepContainer: { template: "<div><slot /></div>" },
            },
        },
    });
    await settle();
    return wrapper;
};

describe("MyPermissions", () => {
    beforeEach(() => {
        fetchSelfPermissions.mockReset().mockResolvedValue([]);
        fetchSelfApplicationRoles.mockReset().mockResolvedValue([]);
    });

    it("lists an application administrator's permission", async () => {
        fetchSelfPermissions.mockResolvedValue([
            {
                css_integration_id: 22264,
                environment: "dev",
                application_name: "FREP",
                role: "APP_ADMIN",
                role_description: "Application administrator",
                role_name: "APP_ADMIN_22264_DEV",
            },
        ]);

        const wrapper = await mountView();

        expect(wrapper.text()).toContain("FREP");
        expect(wrapper.text()).toContain("DEV");
        expect(wrapper.text()).toContain("Application administrator");
    });

    it("shows a dash for FAM_ADMIN rather than an empty environment", async () => {
        fetchSelfPermissions.mockResolvedValue([
            {
                css_integration_id: null,
                environment: null,
                application_name: "All applications",
                role: "FAM_ADMIN",
                role_description: "FAM administrator",
                role_name: "FAM_ADMIN",
            },
        ]);

        const wrapper = await mountView();

        expect(wrapper.text()).toContain("All applications");
        expect(wrapper.text()).toContain("—");
    });

    it("tells a caller who administers nothing, rather than showing an error", async () => {
        const wrapper = await mountView();

        expect(wrapper.text()).toContain("do not administer any applications");
    });

    it("reports a failure to load", async () => {
        fetchSelfPermissions.mockRejectedValue(new Error("boom"));

        const wrapper = await mountView();

        expect(wrapper.text()).toContain("could not be loaded");
    });

    it("lists an application role the user holds, described from its sidecar", async () => {
        fetchSelfApplicationRoles.mockResolvedValue([
            {
                css_integration_id: 22264,
                environment: "dev",
                application_name: "FREP",
                role_name: "FREP_ADMINISTRATOR",
                base_role_name: "FREP_ADMINISTRATOR",
                role_description: "FREP Administrator",
                scopes: [],
            },
        ]);

        const wrapper = await mountView();

        expect(wrapper.text()).toContain("FREP Administrator");
    });

    it("shows one row per scope, with the district named", async () => {
        fetchSelfApplicationRoles.mockResolvedValue(
            ["DCC", "DKA"].map((district) => ({
                css_integration_id: 22264,
                environment: "dev",
                application_name: "FREP",
                role_name: `CHR_FREP_EDITOR_DISTRICT-${district}`,
                base_role_name: "CHR_FREP_EDITOR",
                role_description: "Submitter (CHR)",
                scopes: [{ type: "DISTRICT", value: district, label: undefined }],
            }))
        );

        const wrapper = await mountView();

        expect(wrapper.text()).toContain("DCC");
        expect(wrapper.text()).toContain("DKA");
    });

    it("falls back to the role code when a role has no description", async () => {
        fetchSelfApplicationRoles.mockResolvedValue([
            {
                css_integration_id: 6538,
                environment: "prod",
                application_name: "FOM",
                role_name: "FOM_SUBMITTER",
                base_role_name: "FOM_SUBMITTER",
                role_description: null,
                scopes: [],
            },
        ]);

        const wrapper = await mountView();

        // A blank cell would read as "no role", which is worse than the code.
        expect(wrapper.text()).toContain("FOM_SUBMITTER");
    });

    it("says so when the user holds no application roles", async () => {
        const wrapper = await mountView();

        expect(wrapper.text()).toContain("hold no roles in any application");
    });

    it("shows the administrative half even when application roles fail", async () => {
        fetchSelfApplicationRoles.mockRejectedValue(new Error("CSS down"));
        fetchSelfPermissions.mockResolvedValue([
            {
                css_integration_id: null,
                environment: null,
                application_name: "All applications",
                role: "FAM_ADMIN",
                role_description: "FAM administrator",
                role_name: "FAM_ADMIN",
            },
        ]);

        const wrapper = await mountView();

        // The two halves are independent; one failing must not empty the other.
        expect(wrapper.text()).toContain("FAM administrator");
        expect(wrapper.text()).toContain("application roles could not be loaded");
    });
});
