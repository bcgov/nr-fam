import { QueryClient, VueQueryPlugin } from "@tanstack/vue-query";
import { flushPromises, mount } from "@vue/test-utils";
import { UserType } from "fam-api/model";
import PrimeVue from "primevue/config";
import Toast from "primevue/toast";
import ToastService from "primevue/toastservice";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { defineComponent, h } from "vue";

/**
 * The grant form, driven the way a person drives it.
 *
 * These exist because of a bug the type checker cannot see: the view listened
 * for `@update:selected-users` while UserSearch emits `user-selection-update`.
 * Vue treats an unrecognised listener as a fallthrough attribute rather than an
 * error, so the handler silently never ran, the users field stayed empty,
 * validation failed, and the Grant permission button did nothing at all.
 *
 * Nothing below asserts the event *name* directly - that would just restate the
 * template. They drive the component through its child's real emits, so a
 * mismatch breaks them.
 *
 * The other silent failure is which role a scope lands on: every picker writes
 * through the same setFieldValue, addressing its own role by path.
 */
const createAssignment = vi.fn();

vi.mock("@/services/ApiServiceFactory", () => ({
    AdminMgmtApiService: {
        cssIntegrationsApi: {
            getCssApplicationRoles: () =>
                Promise.resolve({ data: [ROLE, DISTRICT_ROLE, COMPOUND_ROLE] }),
            createCssUserRoleAssignment: (...args: unknown[]) =>
                createAssignment(...args),
        },
    },
    AppActlApiService: {},
}));

const push = vi.fn();
vi.mock("vue-router", () => ({
    useRouter: () => ({ push }),
}));

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

/** Scoped both ways, so it is granted per district/client pair. */
const COMPOUND_ROLE = {
    name: "CHR_FREP_VIEWER",
    display_name: "Viewer (CHR)",
    description: null,
    composite: false,
    composites: [],
    role_type_district: true,
    role_type_client: true,
};

const IDIR_USER = {
    userId: "JSMITH",
    guid: "AAAA1111",
    email: "jane@gov.bc.ca",
};

const DCC = { org_unit_code: "DCC", org_unit_name: "Cariboo-Chilcotin" };
const DKA = { org_unit_code: "DKA", org_unit_name: "Kamloops" };
const ACME = { forest_client_number: "00001012", client_name: "ACME LTD." };

/** Stands in for UserSearch, declaring the same events the real one emits. */
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

/** Every view mounted by a test, so afterEach can take its toasts down. */
const mounted: Array<{ unmount: () => void }> = [];

/** Set by mountView, so a test can watch what the grant invalidates. */
let invalidate: ReturnType<typeof vi.fn>;

const mountView = async () => {
    const AddAppPermission = (await import("./index.vue")).default;

    const queryClient = new QueryClient({
        defaultOptions: { queries: { retry: false } },
    });
    invalidate = vi.fn();
    queryClient.invalidateQueries = invalidate as never;

    // The real Toast, beside the view. App.vue mounts one above the router view
    // precisely so a toast raised just before the redirect still renders; a spec
    // that mounted only the form could not tell that chain from a broken one.
    const Harness = defineComponent({
        props: { integrationId: Number, environment: String },
        setup: (props) => () =>
            h("div", [h(Toast), h(AddAppPermission, props as never)]),
    });

    const wrapper = mount(Harness, {
        props: { integrationId: 22264, environment: "dev" },
        global: {
            plugins: [[VueQueryPlugin, { queryClient }], PrimeVue, ToastService],
            stubs: {
                UserSearch: UserSearchStub,
                DistrictSelectTable: DistrictStub,
                ForestClientAddTable: ForestClientStub,
                PageTitle: true,
                // Renders its title, so a test can tell a withheld step from
                // one that is merely empty.
                StepContainer: {
                    props: ["title"],
                    template: "<div><h3>{{ title }}</h3><slot /></div>",
                },
                Button: {
                    props: ["label", "type", "disabled"],
                    template:
                        "<button :type=\"type\" :disabled=\"disabled\" @click=\"$emit('click')\">{{ label }}</button>",
                },
            },
        },
    });

    mounted.push(wrapper);
    await flushPromises();
    return wrapper;
};

/**
 * Submitting is several async hops - vee-validate validates, then the mutation
 * runs, then its promise settles - so one flush is not enough. Under-flushing
 * does not fail cleanly either: the request lands during the *next* test, which
 * then sees a call it did not make.
 */
const submit = async (wrapper: any) => {
    await wrapper.find("form").trigger("submit");
    // Vue Query defers the mutation onto a task, not just a microtask, so
    // flushing promises alone leaves it pending.
    for (let i = 0; i < 6; i++) {
        await flushPromises();
        await new Promise((resolve) => setTimeout(resolve, 0));
    }
};

const settle = async () => {
    for (let i = 0; i < 5; i++) {
        await flushPromises();
        await new Promise((resolve) => setTimeout(resolve, 0));
    }
};

/** Ticks a role through the real checkbox list, by its visible label. */
const tickRole = async (wrapper: any, label: string) => {
    const checkbox = wrapper
        .findAll(".role-multi-select-table .p-checkbox input")
        .find((input: any) => input.attributes("aria-label") === label);
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

const selectUsers = async (wrapper: any, users: unknown[]) => {
    await wrapper
        .findComponent(UserSearchStub)
        .vm.$emit("user-selection-update", users);
    await settle();
};

describe("AddAppPermission", () => {
    beforeEach(() => {
        createAssignment.mockReset().mockResolvedValue({
            data: [
                {
                    role_name: "FREP_ADMINISTRATOR",
                    role_created: false,
                    assigned: true,
                    error_message: null,
                    email_sending_status: "NOT_REQUIRED",
                },
            ],
        });
        push.mockReset();
    });

    // A toast outlives the screen that raised it by design, so without this a
    // later test reads an earlier one's confirmation and passes on it.
    afterEach(() => {
        mounted.forEach((wrapper) => wrapper.unmount());
        mounted.length = 0;
    });

    it("withholds the role step until somebody has been chosen", async () => {
        const wrapper = await mountView();

        // "Which roles are they getting" is not an answerable question yet, and
        // the scope pickers below would be asking for districts for nobody.
        expect(wrapper.text()).not.toContain("Select the roles to grant");
        expect(wrapper.find(".role-multi-select-table").exists()).toBe(false);
    });

    it("offers the role step once a user is chosen", async () => {
        const wrapper = await mountView();
        await selectUsers(wrapper, [IDIR_USER]);

        expect(wrapper.text()).toContain("Select the roles to grant");
        expect(wrapper.find(".role-multi-select-table").exists()).toBe(true);
    });

    it("withdraws it again if every user is removed", async () => {
        const wrapper = await mountView();
        await selectUsers(wrapper, [IDIR_USER]);
        await selectUsers(wrapper, []);

        // The search clears its own selection when the domain changes, so this
        // is reachable without anybody deleting a row by hand.
        expect(wrapper.find(".role-multi-select-table").exists()).toBe(false);
    });

    it("grants to the users the search reported", async () => {
        const wrapper = await mountView();
        await selectUsers(wrapper, [IDIR_USER]);
        await tickRole(wrapper, "FREP Administrator");
        await submit(wrapper);

        expect(createAssignment).toHaveBeenCalledWith(
            22264,
            "dev",
            expect.objectContaining({
                user_guid: "AAAA1111",
                role_name: "FREP_ADMINISTRATOR",
            })
        );
    });

    it("grants against the domain the search is on", async () => {
        const wrapper = await mountView();
        await wrapper
            .findComponent(UserSearchStub)
            .vm.$emit("user-domain-change", UserType.BceidBus);
        await selectUsers(wrapper, [IDIR_USER]);
        await tickRole(wrapper, "FREP Administrator");
        await submit(wrapper);

        // A stale domain grants against the wrong identity provider, which now
        // fails verification rather than silently doing nothing.
        expect(createAssignment).toHaveBeenCalledWith(
            22264,
            "dev",
            expect.objectContaining({ user_type: UserType.BceidBus })
        );
    });

    it("makes one request per user and role pair", async () => {
        const wrapper = await mountView();
        await selectUsers(wrapper, [
            IDIR_USER,
            { userId: "BJONES", guid: "BBBB2222", email: "bo@gov.bc.ca" },
        ]);
        await tickRole(wrapper, "FREP Administrator");
        await tickRole(wrapper, "Submitter (CHR)");
        await setScopeVia(wrapper, DistrictStub, "Submitter (CHR)", [DCC]);
        await submit(wrapper);

        // CSS assigns one role to one user per call, so two people and two
        // roles is four calls.
        expect(createAssignment).toHaveBeenCalledTimes(4);
    });

    it("keeps two roles' scopes apart", async () => {
        const wrapper = await mountView();
        await selectUsers(wrapper, [IDIR_USER]);
        await tickRole(wrapper, "Submitter (CHR)");
        await tickRole(wrapper, "Viewer (CHR)");

        await setScopeVia(wrapper, DistrictStub, "Submitter (CHR)", [DCC]);
        await setScopeVia(wrapper, DistrictStub, "Viewer (CHR)", [DKA]);
        await setScopeVia(wrapper, ForestClientStub, "Viewer (CHR)", [ACME]);
        await submit(wrapper);

        // Every picker addresses its own role by path. A wrong path would put
        // both districts on one role and leave the other empty.
        expect(createAssignment).toHaveBeenCalledWith(
            22264,
            "dev",
            expect.objectContaining({
                role_name: "CHR_FREP_EDITOR",
                scopes: [{ type: "DISTRICT", values: ["DCC"] }],
            })
        );
        expect(createAssignment).toHaveBeenCalledWith(
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

    it("shows no scope card for a role that needs no narrowing", async () => {
        const wrapper = await mountView();
        await selectUsers(wrapper, [IDIR_USER]);
        await tickRole(wrapper, "FREP Administrator");

        // An empty card reads as one that failed to load.
        expect(wrapper.findAllComponents({ name: "RoleScopeCard" })).toHaveLength(0);
        expect(wrapper.text()).toContain("Granted for the whole application");
    });

    it("counts every user against every role's combinations", async () => {
        const wrapper = await mountView();
        await selectUsers(wrapper, [
            IDIR_USER,
            { userId: "BJONES", guid: "BBBB2222" },
        ]);
        await tickRole(wrapper, "Submitter (CHR)");
        await setScopeVia(wrapper, DistrictStub, "Submitter (CHR)", [DCC, DKA]);

        // Two people against two districts is four permissions, and nothing
        // else on the form says so.
        expect(wrapper.text()).toContain("4 permissions");
    });

    it("refuses to submit a role covering more than the backend allows", async () => {
        const districts = Array.from({ length: 51 }, (_, i) => ({
            org_unit_code: `D${i}`,
            org_unit_name: `District ${i}`,
        }));

        const wrapper = await mountView();
        await selectUsers(wrapper, [IDIR_USER]);
        await tickRole(wrapper, "Submitter (CHR)");
        await setScopeVia(wrapper, DistrictStub, "Submitter (CHR)", districts);
        await submit(wrapper);

        expect(createAssignment).not.toHaveBeenCalled();
        expect(wrapper.text()).toContain("Narrow");
    });

    it("says something when the form is incomplete, rather than doing nothing", async () => {
        const wrapper = await mountView();
        await selectUsers(wrapper, [IDIR_USER]);
        await submit(wrapper);

        // The field errors sit above the button on a long form, so without this
        // the button looks broken.
        expect(wrapper.text()).toContain("Check the highlighted fields");
    });

    it("does not grant when no role was chosen", async () => {
        const wrapper = await mountView();
        await selectUsers(wrapper, [IDIR_USER]);
        await submit(wrapper);

        expect(createAssignment).not.toHaveBeenCalled();
    });

    it("will not grant a scoped role with nothing chosen for it", async () => {
        const wrapper = await mountView();
        await selectUsers(wrapper, [IDIR_USER]);
        await tickRole(wrapper, "Submitter (CHR)");
        await submit(wrapper);

        // The grant would name a role that does not exist.
        expect(createAssignment).not.toHaveBeenCalled();
    });

    it("confirms the grant with a toast that survives the redirect", async () => {
        const wrapper = await mountView();
        await selectUsers(wrapper, [IDIR_USER]);
        await tickRole(wrapper, "FREP Administrator");
        await submit(wrapper);

        // Raised before the push, and the Toast lives above the router view, so
        // it is still on screen once Manage permissions has loaded.
        expect(push).toHaveBeenCalled();
        const toast = wrapper.text() + (document.body.textContent ?? "");
        expect(toast).toContain("Permission granted");
        expect(toast).toContain("FREP Administrator");
    });

    it("counts roles and people rather than naming every pair", async () => {
        const wrapper = await mountView();
        await selectUsers(wrapper, [
            IDIR_USER,
            { userId: "BJONES", guid: "BBBB2222" },
        ]);
        await tickRole(wrapper, "FREP Administrator");
        await tickRole(wrapper, "Submitter (CHR)");
        await setScopeVia(wrapper, DistrictStub, "Submitter (CHR)", [DCC]);
        await submit(wrapper);

        // Four outcomes, but two roles and two users - "4 users" would be wrong
        // twice over.
        const toast = wrapper.text() + (document.body.textContent ?? "");
        expect(toast).toContain("2 roles granted to 2 users");
    });

    it("raises no toast when the grant was refused for everybody", async () => {
        createAssignment.mockRejectedValue({
            response: { data: { description: "different organization" } },
        });

        const wrapper = await mountView();
        await selectUsers(wrapper, [IDIR_USER]);
        await tickRole(wrapper, "FREP Administrator");
        await submit(wrapper);

        // Entirely a failure. The banner on Manage permissions reports it; a
        // toast saying the grant happened would contradict it.
        const toast = wrapper.text() + (document.body.textContent ?? "");
        expect(toast).not.toContain("Permission granted");
    });

    it("keeps the pairs that succeeded when one is refused", async () => {
        createAssignment
            .mockResolvedValueOnce({
                data: [{ assigned: true, email_sending_status: "NOT_REQUIRED" }],
            })
            .mockRejectedValueOnce({
                response: { data: { description: "not yours to grant" } },
            });

        const wrapper = await mountView();
        await selectUsers(wrapper, [IDIR_USER]);
        await tickRole(wrapper, "FREP Administrator");
        await tickRole(wrapper, "Submitter (CHR)");
        await setScopeVia(wrapper, DistrictStub, "Submitter (CHR)", [DCC]);
        await submit(wrapper);

        // The first grant happened in CSS and cannot be taken back by failing
        // here, so it is reported rather than discarded.
        const toast = wrapper.text() + (document.body.textContent ?? "");
        expect(toast).toContain("Some permissions were not granted");
        expect(push).toHaveBeenCalled();
    });

    it("refetches the application's users rather than only marking them stale", async () => {
        const wrapper = await mountView();
        await selectUsers(wrapper, [IDIR_USER]);
        await tickRole(wrapper, "FREP Administrator");
        await submit(wrapper);

        // The table is not mounted yet - the redirect is still to come - so
        // whether a merely-stale query refetches depends on options set
        // elsewhere. Asking for the refetch outright does not.
        expect(invalidate).toHaveBeenCalledWith(
            expect.objectContaining({
                queryKey: ["css-user-role-assignments", 22264, "dev"],
                refetchType: "all",
            })
        );
    });
});
