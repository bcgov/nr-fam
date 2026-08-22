import { VueQueryPlugin } from "@tanstack/vue-query";
import { flushPromises, mount } from "@vue/test-utils";
import PrimeVue from "primevue/config";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

/**
 * Which tabs the permissions screen offers, per application.
 *
 * FAM administers itself through the same screen as everything else, but it is
 * not an ordinary application: the APP_ADMIN and DELEGATED_ADMIN roles on its
 * integration all record who administers some *other* application, because that
 * is the only place such a role can sit and still reach FAM's token. Tabs
 * asking "who are FAM's delegated admins" listed those people, none of whom is
 * one.
 */
/*
    PrimeVue's Tabs observes its own strip to position the ink bar, and jsdom has
    no ResizeObserver. Without this the tab list throws on mount and every
    assertion below fails for the wrong reason.
*/
globalThis.ResizeObserver =
    globalThis.ResizeObserver ??
    class {
        observe() {}
        unobserve() {}
        disconnect() {}
    };

const getApplications = vi.fn();
const fetchSelfPermissions = vi.fn();

vi.mock("@/services/ApiServiceFactory", () => ({
    AdminMgmtApiService: {
        cssIntegrationsApi: {
            getCssApplications: () => getApplications(),
            getCssUserRoleAssignments: () => Promise.resolve({ data: [] }),
            getCssApplicationAdministrators: () => Promise.resolve({ data: [] }),
        },
    },
    AppActlApiService: {},
}));

vi.mock("@/services/AuthApiService", () => ({
    fetchSelfPermissions: () => fetchSelfPermissions(),
}));

vi.mock("vue-router", () => ({ useRouter: () => ({ push: vi.fn() }) }));

const FREP = {
    integration_id: 6538,
    environment: "dev",
    name: "FREP",
    description: "FREP (DEV)",
    fam_application: false,
};

const FAM = {
    integration_id: 12345,
    environment: "dev",
    name: "FAM",
    description: "Forests Access Management (DEV)",
    fam_application: true,
};

const settle = async () => {
    for (let i = 0; i < 6; i++) {
        await flushPromises();
        await new Promise((resolve) => setTimeout(resolve, 0));
    }
};

const mounted: Array<{ unmount: () => void }> = [];

const mountView = async () => {
    // Reset between mounts: the chosen application is module-level state, so
    // without this a test starts on whatever the previous one selected.
    const { selectedApp } = await import("@/store/ApplicationState");
    selectedApp.value = undefined;

    const ManagePermissionsView = (await import("./index.vue")).default;

    const wrapper = mount(ManagePermissionsView, {
        global: {
            plugins: [
                PrimeVue,
                [
                    VueQueryPlugin,
                    {
                        queryClientConfig: {
                            defaultOptions: { queries: { retry: false } },
                        },
                    },
                ],
            ],
            stubs: {
                PageTitle: true,
                TablePlaceholder: true,
                NotificationStack: true,
                CssPermissionsTable: true,
                AdministratorsTable: true,
                Button: true,
                Dropdown: {
                    name: "Dropdown",
                    props: ["options"],
                    template: "<div />",
                },
            },
        },
    });

    mounted.push(wrapper);
    await settle();
    return wrapper;
};

/** Chooses an application the way the picker reports one. */
const choose = async (wrapper: any, app: unknown) => {
    wrapper.findComponent({ name: "Dropdown" }).vm.$emit("change", { value: app });
    await settle();
};

const tabLabels = (wrapper: any) =>
    wrapper.findAll(".p-tab").map((tab: any) => tab.text());

describe("ManagePermissionsView", () => {
    beforeEach(() => {
        getApplications.mockReset().mockResolvedValue({ data: [FREP, FAM] });
        // A FAM administrator, who may see the admin tabs on any application.
        fetchSelfPermissions
            .mockReset()
            .mockResolvedValue([{ role: "FAM_ADMIN" }]);
    });

    afterEach(() => {
        mounted.forEach((wrapper) => wrapper.unmount());
        mounted.length = 0;
    });

    it("offers the admin tabs for an ordinary application", async () => {
        const wrapper = await mountView();
        await choose(wrapper, FREP);

        expect(tabLabels(wrapper).join(" ")).toContain("Delegated admins");
        expect(tabLabels(wrapper).join(" ")).toContain("Application admins");
    });

    it("offers only Users for FAM itself", async () => {
        const wrapper = await mountView();
        await choose(wrapper, FAM);

        // Not a permissions question - a FAM admin still cannot see them here,
        // because there is nothing there to see.
        const labels = tabLabels(wrapper).join(" ");
        expect(labels).toContain("Users");
        expect(labels).not.toContain("Delegated admins");
        expect(labels).not.toContain("Application admins");
    });

    it("shows no administrators table for FAM", async () => {
        const wrapper = await mountView();
        await choose(wrapper, FAM);

        // The tab strip is only half of it: a panel left mounted would still
        // fetch the roster and still show other applications' administrators.
        expect(
            wrapper.findComponent({ name: "AdministratorsTable" }).exists()
        ).toBe(false);
    });

    it("falls back to Users when the open tab disappears", async () => {
        const wrapper = await mountView();
        await choose(wrapper, FREP);

        // Open the Delegated admins tab, then switch to an application that
        // does not have one. Left uncontrolled, Tabs keeps its value and the
        // panel below renders nothing at all.
        const delegatedTab = wrapper
            .findAll(".p-tab")
            .find((tab: any) => tab.text().includes("Delegated admins"));
        await delegatedTab!.trigger("click");
        await settle();

        await choose(wrapper, FAM);

        // Every panel is rendered - the inactive ones are only display:none -
        // so asking whether the users table exists would pass either way. What
        // breaks is that NO panel is active: Tabs still points at a value whose
        // panel is gone, and the screen below the strip is blank.
        const active = wrapper.find('.p-tabpanel[data-p-active="true"]');
        expect(active.exists(), "no tab panel is showing at all").toBe(true);
        expect(
            active.findComponent({ name: "CssPermissionsTable" }).exists()
        ).toBe(true);
    });

    it("still hides the admin tabs from somebody who administers nothing", async () => {
        fetchSelfPermissions.mockResolvedValue([]);

        const wrapper = await mountView();
        await choose(wrapper, FREP);

        // The FAM rule is an extra reason to hide them, not a replacement for
        // the permission check.
        expect(tabLabels(wrapper).join(" ")).not.toContain("Delegated admins");
    });
});
