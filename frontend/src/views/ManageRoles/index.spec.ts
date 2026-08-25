import { VueQueryPlugin } from "@tanstack/vue-query";
import { flushPromises, mount } from "@vue/test-utils";
import PrimeVue from "primevue/config";
import ConfirmationService from "primevue/confirmationservice";
import Toast from "primevue/toast";
import ToastService from "primevue/toastservice";
import { MAX_DESCRIPTION_LENGTH } from "./utils";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { defineComponent, h } from "vue";

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

    // A real Toast beside the view, the way App.vue mounts one above the router
    // view. Without it the toast is handed to a service that is not there.
    const Harness = defineComponent({
        setup: () => () => h("div", [h(Toast), h(ManageRoles)]),
    });

    const wrapper = mount(Harness, {
        global: {
            plugins: [VueQueryPlugin, PrimeVue, ConfirmationService, ToastService],
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

    const deleteFirstRole = async (
        wrapper: Awaited<ReturnType<typeof mountView>>
    ) => {
        await wrapper.find("button[title='Delete role']").trigger("click");
        await settle();
        Array.from(document.body.querySelectorAll("button"))
            .find((button) => button.textContent?.trim() === "Delete")!
            .click();
        await settle();
    };

    it("confirms the deletion with a toast naming the role and application", async () => {
        deleteRole.mockResolvedValue({
            data: {
                role_name: "CHR_FREP_EDITOR",
                removed_roles: ["CHR_FREP_EDITOR", "CHR_FREP_EDITOR_DISTRICT-DCC"],
                removed_delegations: ["DELEGATED_ADMIN_6538_DEV__CHR_FREP_EDITOR"],
                members_affected: 3,
            },
        });

        const wrapper = await mountView();
        await deleteFirstRole(wrapper);

        // Read from the toast itself, not the page. The role code and the
        // application name are both in the table behind it, so asserting on the
        // whole document passes whatever the toast says.
        // The toast teleports to the body, so it is read from the document -
        // and read on its own, because the role code and the application name
        // are both in the table behind it. Asserting on the whole page would
        // pass whatever the toast said.
        const toast = document.body.querySelector(".p-toast-message");
        expect(toast, "no toast was raised").toBeTruthy();
        expect(toast!.textContent).toContain("Role deleted");
        expect(toast!.textContent).toContain("CHR_FREP_EDITOR");
        expect(toast!.textContent).toContain("FREP");
    });

    it("does not recite what went with the role", async () => {
        // The derived roles, the members who lost access and the delegations
        // withdrawn are consequences of deleting the role, not separate
        // outcomes. Counting them made a routine deletion read like an
        // incident report.
        deleteRole.mockResolvedValue({
            data: {
                role_name: "CHR_FREP_EDITOR",
                removed_roles: ["CHR_FREP_EDITOR", "CHR_FREP_EDITOR_DISTRICT-DCC"],
                removed_delegations: ["DELEGATED_ADMIN_6538_DEV__CHR_FREP_EDITOR"],
                members_affected: 3,
            },
        });

        const wrapper = await mountView();
        await deleteFirstRole(wrapper);

        const shown = wrapper.text() + (document.body.textContent ?? "");
        expect(shown).not.toContain("derived from it");
        expect(shown).not.toContain("lost that access");
        expect(shown).not.toContain("withdrawn");
        // And the toast itself is the one line, not a recital: no counts in it.
        const toast = document.body.querySelector(".p-toast-message");
        expect(toast, "no toast was raised").toBeTruthy();
        expect(toast!.textContent).not.toMatch(/\d/);
    });

    it("leaves no message sitting on the page", async () => {
        // The row has gone from the table below; a line that stays until
        // something else replaces it outlives the thing it describes.
        deleteRole.mockResolvedValue({
            data: {
                role_name: "CHR_FREP_EDITOR",
                removed_roles: ["CHR_FREP_EDITOR"],
                removed_delegations: [],
                members_affected: 0,
            },
        });

        const wrapper = await mountView();
        await deleteFirstRole(wrapper);

        expect(wrapper.find(".created-message").exists()).toBe(false);
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

    it("counts the description against its limit as it is typed", async () => {
        const wrapper = await mountView();

        // Starts at zero rather than blank: a counter that only appears once
        // you are near the limit tells you nothing while you still have room to
        // plan.
        expect(wrapper.find(".char-count").text()).toBe(
            `0 / ${MAX_DESCRIPTION_LENGTH}`
        );

        await wrapper.find("#description").setValue("Allows viewing");
        expect(wrapper.find(".char-count").text()).toBe(
            `14 / ${MAX_DESCRIPTION_LENGTH}`
        );
    });

    it("flags the description when it reaches the limit", async () => {
        const wrapper = await mountView();

        await wrapper.find("#description").setValue("x".repeat(MAX_DESCRIPTION_LENGTH));

        // The field is maxlength-capped, so typing stops with no explanation.
        // The count turning red is the only signal that it did.
        expect(wrapper.find(".char-count").classes()).toContain(
            "fam-error-helper-text"
        );
    });

    it("does not flag it below the limit", async () => {
        const wrapper = await mountView();

        await wrapper
            .find("#description")
            .setValue("x".repeat(MAX_DESCRIPTION_LENGTH - 1));

        expect(wrapper.find(".char-count").classes()).toContain(
            "fam-helper-text"
        );
    });

    it("caps the field at the limit the backend enforces", async () => {
        const wrapper = await mountView();

        // Without this the form would accept what the API rejects, and the
        // count would keep rising past a limit nothing stops.
        expect(wrapper.find("#description").attributes("maxlength")).toBe(
            String(MAX_DESCRIPTION_LENGTH)
        );
    });
});
