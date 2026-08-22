import { VueQueryPlugin } from "@tanstack/vue-query";
import { flushPromises, mount } from "@vue/test-utils";
import { UserType } from "fam-api/model";
import PrimeVue from "primevue/config";
import Toast from "primevue/toast";
import ToastService from "primevue/toastservice";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { defineComponent, h } from "vue";

/**
 * Appointing a delegated administrator, now that it takes several roles at once.
 *
 * Two things must not break. The shape of the request: a delegation has to name
 * the role a grant will actually assign, so if the scope values are derived
 * differently here than on the grant screen the delegation authorises nothing,
 * and the failure only shows up later as a delegated admin being refused.
 *
 * And which role a scope lands on. Every picker on the screen writes through the
 * same setFieldValue, addressing its own role by path - so a wrong path is
 * silent, and puts one role's districts on another.
 */
const createDelegatedAdmin = vi.fn();

vi.mock("@/services/ApiServiceFactory", () => ({
    AdminMgmtApiService: {
        cssIntegrationsApi: {
            getCssApplicationRoles: () =>
                Promise.resolve({ data: [ROLE, DISTRICT_ROLE, COMPOUND_ROLE] }),
            createCssDelegatedAdmin: (...args: unknown[]) =>
                createDelegatedAdmin(...args),
        },
    },
    AppActlApiService: {},
}));

const push = vi.fn();
vi.mock("vue-router", () => ({ useRouter: () => ({ push }) }));

const ROLE = {
    name: "FREP_ADMINISTRATOR",
    display_name: "FREP Administrator",
    description: null,
    composite: false,
    composites: [],
    role_type_district: false,
    role_type_client: false,
};

const DISTRICT_ROLE = {
    name: "CHR_FREP_EDITOR",
    display_name: "Submitter (CHR)",
    description: null,
    composite: false,
    composites: [],
    role_type_district: true,
    role_type_client: false,
};

/** Scoped both ways, so a delegation is created per district/client pair. */
const COMPOUND_ROLE = {
    name: "CHR_FREP_VIEWER",
    display_name: "Viewer (CHR)",
    description: null,
    composite: false,
    composites: [],
    role_type_district: true,
    role_type_client: true,
};

const DCC = { org_unit_code: "DCC", org_unit_name: "Cariboo-Chilcotin" };
const DKA = { org_unit_code: "DKA", org_unit_name: "Kamloops" };
const ACME = { forest_client_number: "00001012", client_name: "ACME LTD." };

/** Stands in for UserSearch, declaring the events the real one emits. */
const UserSearchStub = defineComponent({
    name: "UserSearch",
    emits: ["user-selection-update", "user-domain-change"],
    template: "<div />",
});

/**
 * Stands in for the scope pickers, which reach the districts and Forest Client
 * APIs. It keeps the props that matter: the field path the card handed it, and
 * the setter it writes through.
 */
const pickerStub = (name: string) =>
    defineComponent({
        name,
        props: {
            fieldId: { type: String, required: true },
            selected: { type: Array, default: () => [] },
            setFieldValue: { type: Function, required: true },
        },
        template: "<div />",
    });

const DistrictStub = pickerStub("DistrictSelectTable");
const ForestClientStub = pickerStub("ForestClientAddTable");

const settle = async () => {
    for (let i = 0; i < 5; i++) {
        await flushPromises();
        await new Promise((resolve) => setTimeout(resolve, 0));
    }
};

const mounted: Array<{ unmount: () => void }> = [];

const mountView = async () => {
    const AddDelegatedAdmin = (await import("./index.vue")).default;

    // A real Toast beside the view, the way App.vue mounts one above the router
    // view, so a toast raised just before the redirect is observable.
    const Harness = defineComponent({
        props: { integrationId: Number, environment: String },
        setup: (props) => () =>
            h("div", [h(Toast), h(AddDelegatedAdmin, props as never)]),
    });

    const wrapper = mount(Harness, {
        props: { integrationId: 22264, environment: "dev" },
        global: {
            plugins: [VueQueryPlugin, PrimeVue, ToastService],
            stubs: {
                UserSearch: UserSearchStub,
                DistrictSelectTable: DistrictStub,
                ForestClientAddTable: ForestClientStub,
                PageTitle: true,
                Button: {
                    props: ["label", "type", "disabled"],
                    template:
                        "<button :type=\"type\" :disabled=\"disabled\" @click=\"$emit('click')\">{{ label }}</button>",
                },
            },
        },
    });

    mounted.push(wrapper);
    await settle();
    return wrapper;
};

const selectUser = async (wrapper: any) => {
    await wrapper
        .findComponent(UserSearchStub)
        .vm.$emit("user-selection-update", [
            { userId: "JSMITH", guid: "AABB1122", email: "jane@gov.bc.ca" },
        ]);
    await wrapper
        .findComponent(UserSearchStub)
        .vm.$emit("user-domain-change", UserType.Idir);
    await settle();
};

/** Ticks a role through the real checkbox list, by its visible label. */
const tickRole = async (wrapper: any, label: string) => {
    const checkbox = wrapper
        .findAll(".role-multi-select-table .p-checkbox input")
        .find(
            (input: any) => input.attributes("aria-label") === label
        );
    expect(checkbox, `no checkbox for ${label}`).toBeTruthy();
    await checkbox.trigger("change");
    await settle();
};

/**
 * Writes a scope through the picker the card gave that role, using the path the
 * card supplied - so a wrong path fails here rather than silently landing on
 * another role.
 */
const setScopeVia = async (
    wrapper: any,
    stub: any,
    roleLabel: string,
    value: unknown
) => {
    const card = wrapper
        .findAllComponents({ name: "RoleScopeCard" })
        .find((c: any) => c.props("selection").role.display_name === roleLabel);
    expect(card, `no card for ${roleLabel}`).toBeTruthy();

    const picker = card.findComponent(stub);
    picker.props("setFieldValue")(picker.props("fieldId"), value);
    await settle();
};

const submit = async (wrapper: any) => {
    await wrapper.find("form").trigger("submit");
    await settle();
};

describe("AddDelegatedAdmin", () => {
    beforeEach(() => {
        createDelegatedAdmin.mockReset().mockResolvedValue({ data: [] });
        push.mockReset();
    });

    afterEach(() => {
        mounted.forEach((wrapper) => wrapper.unmount());
        mounted.length = 0;
    });

    it("withholds the roles until somebody has been chosen", async () => {
        const wrapper = await mountView();

        // "What may they grant" is not an answerable question yet.
        expect(wrapper.text()).not.toContain("Select the roles they may grant");
        expect(wrapper.find(".role-multi-select-table").exists()).toBe(false);
    });

    it("offers the roles once a user is chosen", async () => {
        const wrapper = await mountView();
        await selectUser(wrapper);

        expect(wrapper.text()).toContain("Select the roles they may grant");
        expect(wrapper.find(".role-multi-select-table").exists()).toBe(true);
    });

    it("shows no scope step for a role that needs no narrowing", async () => {
        const wrapper = await mountView();
        await selectUser(wrapper);
        await tickRole(wrapper, "FREP Administrator");

        // An empty card reads as one that failed to load.
        expect(wrapper.findAllComponents({ name: "RoleScopeCard" })).toHaveLength(0);
        expect(wrapper.text()).toContain("Granted for the whole application");
    });

    it("opens a card only for the roles that are scoped", async () => {
        const wrapper = await mountView();
        await selectUser(wrapper);
        await tickRole(wrapper, "FREP Administrator");
        await tickRole(wrapper, "Submitter (CHR)");

        const cards = wrapper.findAllComponents({ name: "RoleScopeCard" });
        expect(cards).toHaveLength(1);
        expect(cards[0].props("selection").role.name).toBe("CHR_FREP_EDITOR");
    });

    it("appoints for an unscoped role", async () => {
        const wrapper = await mountView();
        await selectUser(wrapper);
        await tickRole(wrapper, "FREP Administrator");
        await submit(wrapper);

        expect(createDelegatedAdmin).toHaveBeenCalledWith(22264, "dev", {
            user_guid: "AABB1122",
            user_type: UserType.Idir,
            role_name: "FREP_ADMINISTRATOR",
            scopes: [],
        });
    });

    it("sends one delegation request carrying every district", async () => {
        const wrapper = await mountView();
        await selectUser(wrapper);
        await tickRole(wrapper, "Submitter (CHR)");
        await setScopeVia(wrapper, DistrictStub, "Submitter (CHR)", [DCC, DKA]);
        await submit(wrapper);

        // The backend turns these into one delegation each; sending the base
        // role alone would delegate something nobody is ever granted.
        expect(createDelegatedAdmin).toHaveBeenCalledWith(
            22264,
            "dev",
            expect.objectContaining({
                role_name: "CHR_FREP_EDITOR",
                scopes: [{ type: "DISTRICT", values: ["DCC", "DKA"] }],
            })
        );
    });

    it("makes one request per role, each with its own scope", async () => {
        const wrapper = await mountView();
        await selectUser(wrapper);
        await tickRole(wrapper, "FREP Administrator");
        await tickRole(wrapper, "Submitter (CHR)");
        await setScopeVia(wrapper, DistrictStub, "Submitter (CHR)", [DCC]);
        await submit(wrapper);

        // The scope belongs to the role. Leaking it onto the unscoped one would
        // delegate a role that does not exist.
        expect(createDelegatedAdmin).toHaveBeenCalledTimes(2);
        expect(createDelegatedAdmin).toHaveBeenCalledWith(
            22264,
            "dev",
            expect.objectContaining({
                role_name: "FREP_ADMINISTRATOR",
                scopes: [],
            })
        );
        expect(createDelegatedAdmin).toHaveBeenCalledWith(
            22264,
            "dev",
            expect.objectContaining({
                role_name: "CHR_FREP_EDITOR",
                scopes: [{ type: "DISTRICT", values: ["DCC"] }],
            })
        );
    });

    it("keeps two scoped roles' selections apart", async () => {
        const wrapper = await mountView();
        await selectUser(wrapper);
        await tickRole(wrapper, "Submitter (CHR)");
        await tickRole(wrapper, "Viewer (CHR)");

        await setScopeVia(wrapper, DistrictStub, "Submitter (CHR)", [DCC]);
        await setScopeVia(wrapper, DistrictStub, "Viewer (CHR)", [DKA]);
        await setScopeVia(wrapper, ForestClientStub, "Viewer (CHR)", [ACME]);
        await submit(wrapper);

        // Each picker addresses its own role by path. A wrong path would put
        // both districts on one role and leave the other empty.
        expect(createDelegatedAdmin).toHaveBeenCalledWith(
            22264,
            "dev",
            expect.objectContaining({
                role_name: "CHR_FREP_EDITOR",
                scopes: [{ type: "DISTRICT", values: ["DCC"] }],
            })
        );
        expect(createDelegatedAdmin).toHaveBeenCalledWith(
            22264,
            "dev",
            expect.objectContaining({
                role_name: "CHR_FREP_VIEWER",
                scopes: [
                    { type: "DISTRICT", values: ["DKA"] },
                    { type: "FOREST_CLIENT", values: ["00001012"] },
                ],
            })
        );
    });

    it("drops a role's scope when the role is unticked", async () => {
        const wrapper = await mountView();
        await selectUser(wrapper);
        await tickRole(wrapper, "Submitter (CHR)");
        await setScopeVia(wrapper, DistrictStub, "Submitter (CHR)", [DCC, DKA]);

        await tickRole(wrapper, "Submitter (CHR)");
        await tickRole(wrapper, "Submitter (CHR)");

        // Kept, a selection somebody thought they had cleared would be
        // re-submitted the moment they changed their mind back.
        const card = wrapper.findAllComponents({ name: "RoleScopeCard" })[0];
        expect(card.props("selection").districts).toEqual([]);
    });

    it("counts a compound role's delegations as the cross-product", async () => {
        const wrapper = await mountView();
        await selectUser(wrapper);
        await tickRole(wrapper, "Viewer (CHR)");
        await setScopeVia(wrapper, DistrictStub, "Viewer (CHR)", [DCC, DKA]);
        await setScopeVia(wrapper, ForestClientStub, "Viewer (CHR)", [ACME]);

        // Two districts against one organisation is two pairs, not three
        // selections - and nothing else on the form says so.
        expect(wrapper.text()).toContain("2 delegations");
    });

    it("refuses to submit a role that covers more than the backend allows", async () => {
        const districts = Array.from({ length: 51 }, (_, i) => ({
            org_unit_code: `D${i}`,
            org_unit_name: `District ${i}`,
        }));

        const wrapper = await mountView();
        await selectUser(wrapper);
        await tickRole(wrapper, "Submitter (CHR)");
        await setScopeVia(wrapper, DistrictStub, "Submitter (CHR)", districts);
        await submit(wrapper);

        // The backend refuses this outright; finding out on submit would mean
        // redoing the whole selection.
        expect(createDelegatedAdmin).not.toHaveBeenCalled();
        expect(wrapper.text()).toContain("Narrow");
    });

    it("does not appoint when no user has been chosen", async () => {
        const wrapper = await mountView();
        await submit(wrapper);

        expect(createDelegatedAdmin).not.toHaveBeenCalled();
    });

    it("does not appoint when no role has been chosen", async () => {
        const wrapper = await mountView();
        await selectUser(wrapper);
        await submit(wrapper);

        expect(createDelegatedAdmin).not.toHaveBeenCalled();
    });

    it("will not appoint a scoped role with nothing chosen for it", async () => {
        const wrapper = await mountView();
        await selectUser(wrapper);
        await tickRole(wrapper, "Submitter (CHR)");
        await submit(wrapper);

        // The delegation would name a role nobody is ever granted.
        expect(createDelegatedAdmin).not.toHaveBeenCalled();
    });

    it("surfaces the backend's refusal", async () => {
        createDelegatedAdmin.mockRejectedValue({
            response: {
                data: {
                    description:
                        "Altering permission privilege of self is not allowed.",
                },
            },
        });

        const wrapper = await mountView();
        await selectUser(wrapper);
        await tickRole(wrapper, "FREP Administrator");
        await submit(wrapper);

        expect(wrapper.text()).toContain("self is not allowed");
        // Nothing landed, so the form stays put with its answers intact.
        expect(push).not.toHaveBeenCalled();
    });

    it("keeps the roles that succeeded when one of several is refused", async () => {
        createDelegatedAdmin
            .mockResolvedValueOnce({ data: [] })
            .mockRejectedValueOnce({
                response: { data: { description: "not yours to delegate" } },
            });

        const wrapper = await mountView();
        await selectUser(wrapper);
        await tickRole(wrapper, "FREP Administrator");
        await tickRole(wrapper, "Submitter (CHR)");
        await setScopeVia(wrapper, DistrictStub, "Submitter (CHR)", [DCC]);
        await submit(wrapper);

        // The first delegation happened in CSS and cannot be taken back by
        // failing here, so it is reported rather than discarded.
        const toast = wrapper.text() + (document.body.textContent ?? "");
        expect(toast).toContain("Some roles were not delegated");
        expect(push).toHaveBeenCalled();
    });

    it("confirms a clean appointment with a toast", async () => {
        const wrapper = await mountView();
        await selectUser(wrapper);
        await tickRole(wrapper, "FREP Administrator");
        await submit(wrapper);

        const toast = wrapper.text() + (document.body.textContent ?? "");
        expect(toast).toContain("Delegated admin added");
        expect(toast).toContain("JSMITH");
    });

    it("returns to Manage permissions after appointing", async () => {
        const wrapper = await mountView();
        await selectUser(wrapper);
        await tickRole(wrapper, "FREP Administrator");
        await submit(wrapper);

        expect(push).toHaveBeenCalledWith(
            expect.objectContaining({ name: "ManagePermissions" })
        );
    });
});
