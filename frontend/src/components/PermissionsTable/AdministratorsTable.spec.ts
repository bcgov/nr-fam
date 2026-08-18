import { VueQueryPlugin } from "@tanstack/vue-query";
import { flushPromises, mount } from "@vue/test-utils";
import { beforeEach, describe, expect, it, vi } from "vitest";

/**
 * The Delegated admins / Application admins tabs.
 *
 * The thing most likely to be wrong is silent: the roster lives on FAM's own
 * integration, and asking the application's integration instead returns an empty
 * list rather than an error - a tab that looks fine and is simply always empty.
 * So the call itself is asserted, not only what renders.
 */
const getAdministrators = vi.fn();

vi.mock("@/services/ApiServiceFactory", () => ({
    AdminMgmtApiService: {
        cssIntegrationsApi: {
            getCssApplicationAdministrators: (...args: unknown[]) =>
                getAdministrators(...args),
        },
    },
    AppActlApiService: {},
}));

const settle = async () => {
    for (let i = 0; i < 5; i++) {
        await flushPromises();
        await new Promise((resolve) => setTimeout(resolve, 0));
    }
};

const mountTable = async (tier = "APP_ADMIN") => {
    const AdministratorsTable = (await import("./AdministratorsTable.vue")).default;
    const wrapper = mount(AdministratorsTable, {
        props: {
            integrationId: 22264,
            environment: "dev",
            tier: tier as never,
            appName: "FREP",
        },
        global: {
            plugins: [
                [
                    VueQueryPlugin,
                    {
                        queryClientConfig: {
                            defaultOptions: { queries: { retry: false } },
                        },
                    },
                ],
            ],
        },
    });
    await settle();
    return wrapper;
};

describe("AdministratorsTable", () => {
    beforeEach(() => {
        getAdministrators.mockReset().mockResolvedValue({ data: [] });
    });

    it("asks for the tier it was given, for this application and environment", async () => {
        await mountTable("DELEGATED_ADMIN");

        expect(getAdministrators).toHaveBeenCalledWith(
            22264,
            "dev",
            "DELEGATED_ADMIN"
        );
    });

    it("lists an administrator", async () => {
        getAdministrators.mockResolvedValue({
            data: [
                {
                    username: "JSMITH",
                    user_guid: "AABB1122",
                    domain: "IDIR",
                    first_name: "Jane",
                    last_name: "Smith",
                    email: "jane@gov.bc.ca",
                    tier: "APP_ADMIN",
                    role_name: "APP_ADMIN_22264_DEV",
                },
            ],
        });

        const wrapper = await mountTable();

        expect(wrapper.text()).toContain("Jane Smith");
        expect(wrapper.text()).toContain("jane@gov.bc.ca");
        expect(wrapper.text()).toContain("IDIR");
    });

    it("says the application has none rather than showing an empty table", async () => {
        const wrapper = await mountTable();

        expect(wrapper.text()).toContain("no administrators at this level");
    });

    it("dashes a name for somebody who has never signed in", async () => {
        getAdministrators.mockResolvedValue({
            data: [
                {
                    username: "abc@azureidir",
                    user_guid: "ABC",
                    domain: "IDIR",
                    first_name: null,
                    last_name: null,
                    email: null,
                    tier: "APP_ADMIN",
                    role_name: "APP_ADMIN_22264_DEV",
                },
            ],
        });

        const wrapper = await mountTable();

        // CSS holds only a username until the person first logs in.
        expect(wrapper.text()).toContain("abc@azureidir");
        expect(wrapper.text()).toContain("—");
    });

    it("shows the backend's reason rather than a generic retry message", async () => {
        getAdministrators.mockRejectedValue({
            response: {
                data: {
                    description:
                        "FAM's own CSS integration id is not configured, so its administrators cannot be read.",
                },
            },
        });

        const wrapper = await mountTable();

        // "Please try again" would be useless advice for a configuration fault.
        expect(wrapper.text()).toContain("is not configured");
    });

    it("falls back to a generic message when there is no reason", async () => {
        getAdministrators.mockRejectedValue(new Error("Network Error"));

        const wrapper = await mountTable();

        expect(wrapper.text()).toContain("Network Error");
    });
});
