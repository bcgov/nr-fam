import { VueQueryPlugin } from "@tanstack/vue-query";
import { flushPromises, mount } from "@vue/test-utils";
import PrimeVue from "primevue/config";
import ConfirmationService from "primevue/confirmationservice";
import { beforeEach, describe, expect, it, vi } from "vitest";

/**
 * The Manage roles table, driven through the DOM rather than through internals.
 *
 * Deleting a role revokes access from everyone holding it and cannot be undone,
 * so the two things that must not break are that nothing is deleted without a
 * confirmation, and that the count shown in that confirmation is the real one.
 * Both are wiring the type checker cannot see - a listener that never fires or
 * a count read from the wrong key still compiles.
 */
const deleteRole = vi.fn();
const createAll = vi.fn();
const getRoles = vi.fn();
const getMemberCounts = vi.fn();

vi.mock("@/services/ApiServiceFactory", () => ({
    AdminMgmtApiService: {
        cssIntegrationsApi: {
            getCssApplications: () => Promise.resolve({ data: applications() }),
            getCssApplicationRoles: (...args: unknown[]) => getRoles(...args),
            getCssApplicationRoleMemberCounts: (...args: unknown[]) =>
                getMemberCounts(...args),
            createCssApplicationRole: () => Promise.resolve({ data: {} }),
            createCssApplicationRoleAllEnvironments: (...args: unknown[]) =>
                createAll(...args),
            deleteCssApplicationRole: (...args: unknown[]) =>
                deleteRole(...args),
        },
    },
    AppActlApiService: {},
}));

const APP = {
    integration_id: 6538,
    environment: "dev",
    description: "FREP",
    fam_application: false,
};

/** FAM administering itself, which this screen must never offer. */
const FAM_APP = {
    integration_id: 12345,
    environment: "dev",
    description: "Forests Access Management (DEV)",
    fam_application: true,
};

/** What getCssApplications answers, so a test can vary it. */
let applications = () => [APP] as any[];

const SCOPED_ROLE = {
    name: "CHR_FREP_EDITOR",
    display_name: "Submitter (CHR)",
    description: "Allows editing FREP submissions",
    role_code: null,
    composite: true,
    composites: ["HAS_DISTRICT_ROLE"],
    role_type_district: true,
    role_type_client: false,
};

/** Settles Vue Query, which defers onto a task rather than a microtask. */
const settle = async () => {
    for (let i = 0; i < 5; i++) {
        await flushPromises();
        await new Promise((resolve) => setTimeout(resolve, 0));
    }
};

const mountView = async () => {
    const ManageRoles = (await import("./index.vue")).default;

    const wrapper = mount(ManageRoles, {
        global: {
            plugins: [VueQueryPlugin, PrimeVue, ConfirmationService],
            stubs: {
                PageTitle: true,
                SubsectionTitle: true,
                StepContainer: { template: "<div><slot /></div>" },
                // Reports a chosen application the way the real dropdown does.
                Dropdown: {
                    // Named so a test can find it and read the options it was
                    // handed - a closed dropdown draws none of them.
                    name: "Dropdown",
                    props: ["options"],
                    template:
                        "<button class='pick-app' @click=\"$emit('change', { value: options[0] })\" />",
                },
            },
        },
    });

    await settle();
    // Choosing an application is what enables both queries.
    await wrapper.find(".pick-app").trigger("click");
    await settle();
    return wrapper;
};

describe("ManageRoles", () => {
    beforeEach(() => {
        applications = () => [APP];
        deleteRole.mockReset();
        createAll.mockReset().mockResolvedValue({
            data: {
                role_code: "FREP_ADMINISTRATOR",
                description: "FREP Administrator",
                environments: ["dev", "test", "prod"],
                role: {},
            },
        });
        getRoles.mockReset().mockResolvedValue({ data: [SCOPED_ROLE] });
        getMemberCounts.mockReset().mockResolvedValue({
            data: [{ role_name: "CHR_FREP_EDITOR", member_count: 3 }],
        });
    });

    it("shows the member count for a role", async () => {
        const wrapper = await mountView();

        expect(wrapper.text()).toContain("Submitter (CHR)");
        expect(wrapper.text()).toContain("3");
    });

    it("shows a role nobody holds as 0 once the counts have loaded", async () => {
        getMemberCounts.mockResolvedValue({ data: [] });

        const wrapper = await mountView();
        const cells = wrapper.findAll("td").map((cell) => cell.text());

        // The backend omits roles with no members, so an absent entry after a
        // successful load means none - not unknown.
        expect(cells).toContain("0");
    });

    it("does not delete anything until the confirmation is accepted", async () => {
        const wrapper = await mountView();

        await wrapper.find("button[title='Delete role']").trigger("click");
        await settle();

        expect(deleteRole).not.toHaveBeenCalled();
    });

    it("names the role and its real member count in the confirmation", async () => {
        const wrapper = await mountView();

        await wrapper.find("button[title='Delete role']").trigger("click");
        await settle();

        // The dialog teleports out of the component, so read the document.
        const dialog = document.body.textContent ?? "";
        expect(dialog).toContain("Submitter (CHR)");
        expect(dialog).toContain("3");
        expect(dialog).toContain("cannot be undone");
    });

    it("deletes the role when the confirmation is accepted", async () => {
        deleteRole.mockResolvedValue({
            data: {
                role_name: "CHR_FREP_EDITOR",
                removed_roles: ["CHR_FREP_EDITOR", "CHR_FREP_EDITOR_DISTRICT-DCC"],
                removed_delegations: ["DELEGATED_ADMIN_6538_DEV__CHR_FREP_EDITOR"],
                members_affected: 3,
            },
        });

        const wrapper = await mountView();
        await wrapper.find("button[title='Delete role']").trigger("click");
        await settle();

        const accept = Array.from(
            document.body.querySelectorAll("button")
        ).find((button) => button.textContent?.trim() === "Delete");
        expect(accept, "the confirmation should offer a Delete button").toBeTruthy();

        accept!.click();
        await settle();

        expect(deleteRole).toHaveBeenCalledWith(6538, "dev", "CHR_FREP_EDITOR");
    });

    it("reports the roles that went with it", async () => {
        deleteRole.mockResolvedValue({
            data: {
                role_name: "CHR_FREP_EDITOR",
                removed_roles: ["CHR_FREP_EDITOR", "CHR_FREP_EDITOR_DISTRICT-DCC"],
                removed_delegations: ["DELEGATED_ADMIN_6538_DEV__CHR_FREP_EDITOR"],
                members_affected: 3,
            },
        });

        const wrapper = await mountView();
        await wrapper.find("button[title='Delete role']").trigger("click");
        await settle();
        Array.from(document.body.querySelectorAll("button"))
            .find((button) => button.textContent?.trim() === "Delete")!
            .click();
        await settle();

        // One role on screen was two in CSS; saying "deleted" would understate it.
        expect(wrapper.text()).toContain("1 role(s) derived from it");
        expect(wrapper.text()).toContain("3 user(s) lost that access");
        // Silence here would hide that somebody's authority was withdrawn.
        expect(wrapper.text()).toContain("1 delegated admin privilege(s) withdrawn");
    });

    /**
     * Fills the create form the way a person does, through the inputs.
     */
    const fillForm = async (wrapper: any) => {
        await wrapper.find("input#roleCode").setValue("FREP_ADMINISTRATOR");
        await wrapper.find("input#roleName").setValue("FREP Administrator");
        await settle();
    };

    const clickButton = async (wrapper: any, label: string) => {
        const button = wrapper
            .findAll("button")
            .find((b: any) => b.text().trim() === label);
        expect(button, `expected a "${label}" button`).toBeTruthy();
        await button.trigger("click");
        await settle();
    };

    it("creates in every environment without sending one", async () => {
        const wrapper = await mountView();
        await fillForm(wrapper);
        await clickButton(wrapper, "Create in all environments");

        // Integration id and body only - the backend uses the integration's own
        // environment list rather than one this screen picks.
        expect(createAll).toHaveBeenCalledWith(6538, {
            role_code: "FREP_ADMINISTRATOR",
            role_name: "FREP Administrator",
            description: "",
            requires_district: false,
            requires_forest_client: false,
        });
    });

    it("reports which environments the role was created in", async () => {
        const wrapper = await mountView();
        await fillForm(wrapper);
        await clickButton(wrapper, "Create in all environments");

        expect(wrapper.text()).toContain("dev, test, prod");
    });

    it("does not call the API when the form is invalid", async () => {
        const wrapper = await mountView();
        // No role code or description entered.
        await clickButton(wrapper, "Create in all environments");

        expect(createAll).not.toHaveBeenCalled();
    });

    it("surfaces the backend's clash message naming the environments", async () => {
        createAll.mockRejectedValue({
            response: {
                data: {
                    description:
                        "A role named FREP_ADMINISTRATOR already exists in prod. Nothing was created.",
                },
            },
        });

        const wrapper = await mountView();
        await fillForm(wrapper);
        await clickButton(wrapper, "Create in all environments");

        expect(wrapper.text()).toContain("already exists in prod");
    });

    it("does not offer FAM's own application", async () => {
        // FAM's integration holds FAM_ADMIN plus the APP_ADMIN_<id>_<ENV> and
        // DELEGATED_ADMIN_... roles it generates as administrators are
        // appointed. Deleting APP_ADMIN_22264_PROD from this screen would strip
        // every administrator of that application at once, and nothing here
        // would say so.
        applications = () => [APP, FAM_APP];

        const wrapper = await mountView();

        // The options the picker was handed, not what it has drawn: a closed
        // dropdown renders none of them, so reading the page text would pass
        // whether the filter ran or not.
        const offered = wrapper
            .findComponent({ name: "Dropdown" })
            .props("options") as any[];

        expect(offered.map((app) => app.description)).toEqual(["FREP"]);
    });

    it("still offers ordinary applications alongside it", async () => {
        // The filter is on the flag, not on the list being short - FAM first
        // here, so dropping only the head would fail.
        applications = () => [FAM_APP, APP, { ...APP, description: "SILVA" }];

        const wrapper = await mountView();

        const offered = wrapper
            .findComponent({ name: "Dropdown" })
            .props("options") as any[];

        expect(offered.map((app) => app.description)).toEqual(["FREP", "SILVA"]);
    });
});
