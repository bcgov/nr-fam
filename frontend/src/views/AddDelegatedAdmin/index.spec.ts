import { VueQueryPlugin } from "@tanstack/vue-query";
import { flushPromises, mount } from "@vue/test-utils";
import { UserType } from "fam-api/model";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { defineComponent } from "vue";

/**
 * Appointing a delegated administrator.
 *
 * The part that must not break is the shape of the request: a delegation has to
 * name the role a grant will actually assign, so if the scope values are derived
 * differently here than on the grant screen, the delegation authorises nothing
 * and the failure only shows up later, as a delegated admin being refused.
 */
const createDelegatedAdmin = vi.fn();

vi.mock("@/services/ApiServiceFactory", () => ({
    AdminMgmtApiService: {
        cssIntegrationsApi: {
            getCssApplicationRoles: () => Promise.resolve({ data: [ROLE, SCOPED_ROLE] }),
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

const SCOPED_ROLE = {
    name: "CHR_FREP_EDITOR",
    display_name: "Submitter (CHR)",
    description: null,
    composite: false,
    composites: [],
    role_type_district: true,
    role_type_client: false,
};

/** Stands in for UserSearch, declaring the events the real one emits. */
const UserSearchStub = defineComponent({
    name: "UserSearch",
    emits: ["user-selection-update", "user-domain-change"],
    template: "<div />",
});

/** Stands in for RoleSelectTable, which reports through setFieldValue. */
const RoleSelectTableStub = defineComponent({
    name: "RoleSelectTable",
    props: {
        setFieldValue: { type: Function, required: true },
        roleOptions: { type: Array, default: () => [] },
    },
    template: "<div />",
});

const settle = async () => {
    for (let i = 0; i < 5; i++) {
        await flushPromises();
        await new Promise((resolve) => setTimeout(resolve, 0));
    }
};

const mountView = async () => {
    const AddDelegatedAdmin = (await import("./index.vue")).default;
    const wrapper = mount(AddDelegatedAdmin, {
        props: { integrationId: 22264, environment: "dev" },
        global: {
            plugins: [VueQueryPlugin],
            stubs: {
                UserSearch: UserSearchStub,
                RoleSelectTable: RoleSelectTableStub,
                PageTitle: true,
                StepContainer: { template: "<div><slot /></div>" },
                Button: {
                    props: ["label", "type"],
                    template:
                        "<button :type=\"type\" @click=\"$emit('click')\">{{ label }}</button>",
                },
            },
        },
    });
    await settle();
    return wrapper;
};

const selectUser = async (wrapper: any) => {
    await wrapper
        .findComponent(UserSearchStub)
        .vm.$emit("user-selection-update", [
            { userId: "JSMITH", guid: "AABB1122", email: "jane@gov.bc.ca" },
        ]);
    await wrapper.findComponent(UserSearchStub).vm.$emit("user-domain-change", UserType.Idir);
    await settle();
};

const setField = async (wrapper: any, field: string, value: unknown) => {
    const setFieldValue = wrapper
        .findComponent(RoleSelectTableStub)
        .props("setFieldValue") as (field: string, value: unknown) => void;
    setFieldValue(field, value);
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

    it("appoints for an unscoped role", async () => {
        const wrapper = await mountView();
        await selectUser(wrapper);
        await setField(wrapper, "role", ROLE);
        await submit(wrapper);

        expect(createDelegatedAdmin).toHaveBeenCalledWith(22264, "dev", {
            user_guid: "AABB1122",
            user_type: UserType.Idir,
            role_name: "FREP_ADMINISTRATOR",
            scope_type: undefined,
            scope_values: [],
        });
    });

    it("sends one delegation request carrying every district", async () => {
        const wrapper = await mountView();
        await selectUser(wrapper);
        await setField(wrapper, "role", SCOPED_ROLE);
        await setField(wrapper, "districts", [
            { org_unit_code: "DCC", org_unit_name: "Cariboo-Chilcotin" },
            { org_unit_code: "DKA", org_unit_name: "Kamloops" },
        ]);
        await submit(wrapper);

        // The backend turns these into one delegation each; sending the base
        // role alone would delegate something nobody is ever granted.
        expect(createDelegatedAdmin).toHaveBeenCalledWith(
            22264,
            "dev",
            expect.objectContaining({
                role_name: "CHR_FREP_EDITOR",
                scope_type: "DISTRICT",
                scope_values: ["DCC", "DKA"],
            })
        );
    });

    it("does not appoint when no user has been chosen", async () => {
        const wrapper = await mountView();
        await setField(wrapper, "role", ROLE);
        await submit(wrapper);

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
        await setField(wrapper, "role", ROLE);
        await submit(wrapper);

        expect(wrapper.text()).toContain("self is not allowed");
    });

    it("returns to Manage permissions after appointing", async () => {
        const wrapper = await mountView();
        await selectUser(wrapper);
        await setField(wrapper, "role", ROLE);
        await submit(wrapper);

        expect(push).toHaveBeenCalledWith(
            expect.objectContaining({ name: "ManagePermissions" })
        );
    });
});
