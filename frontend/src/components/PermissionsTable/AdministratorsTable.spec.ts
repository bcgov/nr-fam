import { VueQueryPlugin } from "@tanstack/vue-query";
import { flushPromises, mount } from "@vue/test-utils";
import PrimeVue from "primevue/config";
import ConfirmationService from "primevue/confirmationservice";
import Toast from "primevue/toast";
import ToastService from "primevue/toastservice";
import { defineComponent, h } from "vue";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

/**
 * The Delegated admins / Application admins tabs.
 *
 * The thing most likely to be wrong is silent: the roster lives on FAM's own
 * integration, and asking the application's integration instead returns an empty
 * list rather than an error - a tab that looks fine and is simply always empty.
 * So the call itself is asserted, not only what renders.
 */
const getAdministrators = vi.fn();
const deleteDelegatedAdmin = vi.fn();
const deleteApplicationAdmin = vi.fn();

vi.mock("@/services/ApiServiceFactory", () => ({
    AdminMgmtApiService: {
        cssIntegrationsApi: {
            getCssApplicationAdministrators: (...args: unknown[]) =>
                getAdministrators(...args),
            deleteCssDelegatedAdmin: (...args: unknown[]) =>
                deleteDelegatedAdmin(...args),
            deleteCssApplicationAdmin: (...args: unknown[]) =>
                deleteApplicationAdmin(...args),
        },
    },
    AppActlApiService: {},
}));

const APP_ADMIN_ROW = {
    username: "JSMITH",
    user_guid: "AABB1122",
    domain: "IDIR",
    first_name: "Jane",
    last_name: "Smith",
    email: "jane@gov.bc.ca",
    tier: "APP_ADMIN",
    role_name: "APP_ADMIN_22264_DEV",
    scopes: [],
};

/** One delegation, scoped by a district and a forest client at once. */
const DELEGATED_ROW = {
    username: "JSMITH",
    user_guid: "AABB1122",
    domain: "IDIR",
    first_name: "Jane",
    last_name: "Smith",
    email: "jane@gov.bc.ca",
    tier: "DELEGATED_ADMIN",
    role_name:
        "DELEGATED_ADMIN_22264_DEV__CHR_FREP_EDITOR_DISTRICT-DCC_FOREST_CLIENT-00001012",
    delegated_role_name: "CHR_FREP_EDITOR",
    delegated_role_display_name: "Submitter (CHR)",
    scopes: [
        { type: "DISTRICT", value: "DCC", label: "Cariboo-Chilcotin" },
        { type: "FOREST_CLIENT", value: "00001012", label: "ACME LTD." },
    ],
};

/** Clicks the confirmation's accept button, which teleports out of the table. */
const acceptConfirmation = async () => {
    const accept = Array.from(document.body.querySelectorAll("button")).find(
        (button) => button.textContent?.trim() === "Remove"
    );
    expect(accept, "the confirmation should offer a Remove button").toBeTruthy();
    accept!.click();
    await settle();
};

const settle = async () => {
    for (let i = 0; i < 5; i++) {
        await flushPromises();
        await new Promise((resolve) => setTimeout(resolve, 0));
    }
};

/** Every table mounted by a test, so afterEach can take their dialogs down. */
const mounted: Array<{ unmount: () => void }> = [];

const mountTable = async (tier = "APP_ADMIN") => {
    const AdministratorsTable = (await import("./AdministratorsTable.vue")).default;

    // The real Toast, rendered beside the table. App.vue mounts one above the
    // router view; without an equivalent here the toast would be handed to the
    // service and never rendered, so a test could not tell a working chain from
    // a broken one. A render function rather than a template, so the spec does
    // not need Vue's runtime compiler.
    const Harness = defineComponent({
        props: {
            integrationId: Number,
            environment: String,
            tier: String,
            appName: String,
        },
        setup: (props) => () => h("div", [h(Toast), h(AdministratorsTable, props as never)]),
    });

    const wrapper = mount(Harness, {
        props: {
            integrationId: 22264,
            environment: "dev",
            tier: tier as never,
            appName: "FREP",
        },
        global: {
            plugins: [
                PrimeVue,
                ConfirmationService,
                ToastService,
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
    mounted.push(wrapper);
    await settle();
    return wrapper;
};

describe("AdministratorsTable", () => {
    beforeEach(() => {
        getAdministrators.mockReset().mockResolvedValue({ data: [] });
        deleteDelegatedAdmin.mockReset().mockResolvedValue({ data: undefined });
        deleteApplicationAdmin.mockReset().mockResolvedValue({ data: undefined });
    });

    // The dialog teleports to the body, so an assertion on document.body would
    // otherwise read a previous test's wording. Unmounting takes the teleported
    // nodes with it; clearing the body by hand instead would pull the mount
    // point out from under a still-live component.
    afterEach(() => {
        mounted.forEach((wrapper) => wrapper.unmount());
        mounted.length = 0;
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

    it("does not remove anything until the confirmation is accepted", async () => {
        getAdministrators.mockResolvedValue({ data: [APP_ADMIN_ROW] });

        const wrapper = await mountTable();
        await wrapper.find("button[title='Remove administrator']").trigger("click");
        await settle();

        expect(deleteApplicationAdmin).not.toHaveBeenCalled();
    });

    it("removes an application administrator with no role and no scope", async () => {
        getAdministrators.mockResolvedValue({ data: [APP_ADMIN_ROW] });

        const wrapper = await mountTable("APP_ADMIN");
        await wrapper.find("button[title='Remove administrator']").trigger("click");
        await settle();
        await acceptConfirmation();

        // Authorised over the application, not over any one of its roles.
        expect(deleteApplicationAdmin).toHaveBeenCalledWith(22264, "dev", {
            user_guid: "AABB1122",
            user_type: "IDIR",
        });
    });

    it("withdraws exactly the delegation the row stands for", async () => {
        getAdministrators.mockResolvedValue({ data: [DELEGATED_ROW] });

        const wrapper = await mountTable("DELEGATED_ADMIN");
        await wrapper.find("button[title='Remove administrator']").trigger("click");
        await settle();
        await acceptConfirmation();

        // The base role plus every one of this row's scopes, one value each.
        // Sending fewer would name a delegation nobody holds, and the removal
        // would report success having removed nothing.
        expect(deleteDelegatedAdmin).toHaveBeenCalledWith(22264, "dev", {
            user_guid: "AABB1122",
            user_type: "IDIR",
            role_name: "CHR_FREP_EDITOR",
            scopes: [
                { type: "DISTRICT", values: ["DCC"] },
                { type: "FOREST_CLIENT", values: ["00001012"] },
            ],
        });
    });

    it("names the role and scope being withdrawn in the confirmation", async () => {
        getAdministrators.mockResolvedValue({ data: [DELEGATED_ROW] });

        const wrapper = await mountTable("DELEGATED_ADMIN");
        await wrapper.find("button[title='Remove administrator']").trigger("click");
        await settle();

        const dialog = document.body.textContent ?? "";
        expect(dialog).toContain("Submitter (CHR)");
        // The readable label, not the raw code, for both scopes.
        expect(dialog).toContain("Cariboo-Chilcotin");
        expect(dialog).toContain("ACME LTD.");
        expect(dialog).toContain("keep any other roles");
    });

    it("warns an application admin loses the whole application, not one role", async () => {
        getAdministrators.mockResolvedValue({ data: [APP_ADMIN_ROW] });

        const wrapper = await mountTable("APP_ADMIN");
        await wrapper.find("button[title='Remove administrator']").trigger("click");
        await settle();

        const dialog = document.body.textContent ?? "";
        expect(dialog).toContain("application administrator");
        expect(dialog).toContain("appointing other administrators");
    });

    it("removes a BCeID administrator under their own directory", async () => {
        getAdministrators.mockResolvedValue({
            data: [{ ...APP_ADMIN_ROW, domain: "BCEID" }],
        });

        const wrapper = await mountTable("APP_ADMIN");
        await wrapper.find("button[title='Remove administrator']").trigger("click");
        await settle();
        await acceptConfirmation();

        // The same GUID may exist in both directories, so the removal has to
        // say which one it means.
        expect(deleteApplicationAdmin).toHaveBeenCalledWith(
            22264,
            "dev",
            expect.objectContaining({ user_type: "BCEID_BUS" })
        );
    });

    it("shows the delegated role as a pill carrying its name, not its code", async () => {
        getAdministrators.mockResolvedValue({ data: [DELEGATED_ROW] });

        const wrapper = await mountTable("DELEGATED_ADMIN");
        const grantCell = wrapper.findAll("td")[4];

        // The same shape and the same wording the users tab gives the Role
        // column. CHR_FREP_EDITOR is how the role is coded, not what it is
        // called, and nobody administering FREP thinks of it that way.
        expect(grantCell.find(".p-chip").exists()).toBe(true);
        expect(grantCell.text()).toContain("Submitter (CHR)");
        expect(grantCell.text()).not.toContain("CHR_FREP_EDITOR");
    });

    it("falls back to the code for a role with no name", async () => {
        getAdministrators.mockResolvedValue({
            data: [{ ...DELEGATED_ROW, delegated_role_display_name: null }],
        });

        const wrapper = await mountTable("DELEGATED_ADMIN");

        // Every role added by hand in the CSS console has no label sidecar. A
        // technical name beats an empty pill.
        expect(wrapper.findAll("td")[4].text()).toContain("CHR_FREP_EDITOR");
    });

    it("shows each scope as its own chip", async () => {
        getAdministrators.mockResolvedValue({ data: [DELEGATED_ROW] });

        const wrapper = await mountTable("DELEGATED_ADMIN");

        // A compound delegation is two conditions, not one odd value.
        expect(wrapper.findAll(".scope-chips .p-chip")).toHaveLength(2);
    });

    it("offers no scope column on the application admin tab", async () => {
        getAdministrators.mockResolvedValue({ data: [APP_ADMIN_ROW] });

        const wrapper = await mountTable("APP_ADMIN");

        // An application administrator is delegated no role, so neither column
        // has anything to say.
        const headers = wrapper.findAll("th").map((th) => th.text());
        expect(headers).not.toContain("Scope");
        expect(headers).not.toContain("May grant");
    });

    it("cannot remove somebody CSS could not name", async () => {
        getAdministrators.mockResolvedValue({
            data: [{ ...APP_ADMIN_ROW, user_guid: null }],
        });

        const wrapper = await mountTable("APP_ADMIN");
        const button = wrapper.find("button.btn-icon");

        // Nothing to send, so the button says so rather than failing on click.
        expect(button.attributes("disabled")).toBeDefined();
    });

    it("shows the backend's reason when a removal is refused", async () => {
        getAdministrators.mockResolvedValue({ data: [APP_ADMIN_ROW] });
        deleteApplicationAdmin.mockRejectedValue({
            response: {
                data: { description: "You cannot remove your own access." },
            },
        });

        const wrapper = await mountTable("APP_ADMIN");
        await wrapper.find("button[title='Remove administrator']").trigger("click");
        await settle();
        await acceptConfirmation();

        // Self-removal is refused by the backend; the frontend does not know
        // its own GUID, so this message is the only account of what happened.
        expect(wrapper.text()).toContain("You cannot remove your own access.");
    });

    it("reloads the roster after a removal", async () => {
        getAdministrators.mockResolvedValue({ data: [APP_ADMIN_ROW] });

        const wrapper = await mountTable("APP_ADMIN");
        const before = getAdministrators.mock.calls.length;

        await wrapper.find("button[title='Remove administrator']").trigger("click");
        await settle();
        await acceptConfirmation();

        // Otherwise the row stays on screen and the table disagrees with CSS.
        expect(getAdministrators.mock.calls.length).toBeGreaterThan(before);
    });

    it("opens only its own confirmation when both tabs are on screen", async () => {
        // ManagePermissionsView mounts both tiers. A dialog group shared between
        // them would have every mounted dialog answer the same request, so one
        // click would raise two confirmations - and accepting either would run
        // the action queued by whichever table asked.
        getAdministrators.mockResolvedValue({ data: [DELEGATED_ROW] });
        const delegated = await mountTable("DELEGATED_ADMIN");

        getAdministrators.mockResolvedValue({ data: [APP_ADMIN_ROW] });
        await mountTable("APP_ADMIN");

        await delegated
            .find("button[title='Remove administrator']")
            .trigger("click");
        await settle();

        expect(
            document.body.querySelectorAll(".p-confirmdialog")
        ).toHaveLength(1);

        await acceptConfirmation();

        expect(deleteDelegatedAdmin).toHaveBeenCalled();
        expect(deleteApplicationAdmin).not.toHaveBeenCalled();
    });

    it("confirms a delegated withdrawal with a toast naming role and scope", async () => {
        getAdministrators.mockResolvedValue({ data: [DELEGATED_ROW] });

        const wrapper = await mountTable("DELEGATED_ADMIN");
        await wrapper.find("button[title='Remove administrator']").trigger("click");
        await settle();
        await acceptConfirmation();

        // A row disappearing is the only other evidence, and on a long list that
        // can happen off-screen.
        const toast = document.body.textContent ?? "";
        expect(toast).toContain("Delegated admin removed");
        expect(toast).toContain("Submitter (CHR)");
        expect(toast).toContain("Cariboo-Chilcotin");
    });

    it("confirms an application admin removal in its own words", async () => {
        getAdministrators.mockResolvedValue({ data: [APP_ADMIN_ROW] });

        const wrapper = await mountTable("APP_ADMIN");
        await wrapper.find("button[title='Remove administrator']").trigger("click");
        await settle();
        await acceptConfirmation();

        const toast = document.body.textContent ?? "";
        expect(toast).toContain("Application admin removed");
        expect(toast).toContain("no longer an application administrator");
    });

    it("raises no toast when the removal was refused", async () => {
        getAdministrators.mockResolvedValue({ data: [APP_ADMIN_ROW] });
        deleteApplicationAdmin.mockRejectedValue(new Error("boom"));

        const wrapper = await mountTable("APP_ADMIN");
        await wrapper.find("button[title='Remove administrator']").trigger("click");
        await settle();
        await acceptConfirmation();

        // Otherwise the screen says it worked and shows the error at once.
        expect(document.body.textContent ?? "").not.toContain(
            "Application admin removed"
        );
    });
});
