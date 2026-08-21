import { VueQueryPlugin } from "@tanstack/vue-query";
import { flushPromises, mount } from "@vue/test-utils";
import { UserType } from "fam-api/model";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { defineComponent } from "vue";

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
 */
const createAssignment = vi.fn();

vi.mock("@/services/ApiServiceFactory", () => ({
    AdminMgmtApiService: {
        cssIntegrationsApi: {
            getCssApplicationRoles: () => Promise.resolve({ data: [] }),
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

/** Stands in for UserSearch, declaring the same events the real one emits. */
const UserSearchStub = defineComponent({
    name: "UserSearch",
    emits: ["user-selection-update", "user-domain-change"],
    template: "<div />",
});

/**
 * Stands in for RoleSelectTable, which reports a choice by calling the
 * setFieldValue it is handed rather than by emitting.
 */
const RoleSelectTableStub = defineComponent({
    name: "RoleSelectTable",
    props: {
        setFieldValue: { type: Function, required: true },
        roleOptions: { type: Array, default: () => [] },
    },
    template: "<div />",
});

const ROLE = {
    name: "FREP_ADMINISTRATOR",
    description: "FREP Administrator",
    composite: false,
    composites: [],
    role_type_district: false,
    role_type_client: false,
};

const IDIR_USER = {
    userId: "JSMITH",
    guid: "AAAA1111",
    email: "jane@gov.bc.ca",
};

const mountView = async () => {
    const AddAppPermission = (await import("./index.vue")).default;

    const wrapper = mount(AddAppPermission, {
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

    await flushPromises();
    return wrapper;
};

/**
 * Submitting is several async hops - vee-validate validates, then the mutation
 * runs, then its promise settles - so one flush is not enough. Under-flushing
 * does not fail cleanly either: the request lands during the *next* test, which
 * then sees a call it did not make.
 */
const submit = async (wrapper: Awaited<ReturnType<typeof mountView>>) => {
    await wrapper.find("form").trigger("submit");
    // Vue Query defers the mutation onto a task, not just a microtask, so
    // flushing promises alone leaves it pending.
    for (let i = 0; i < 5; i++) {
        await flushPromises();
        await new Promise((resolve) => setTimeout(resolve, 0));
    }
};

const selectRole = async (
    wrapper: Awaited<ReturnType<typeof mountView>>,
    role: unknown
) => {
    // Driven through the prop the real table is given, so a change to that
    // contract breaks these too.
    const setFieldValue = wrapper
        .findComponent(RoleSelectTableStub)
        .props("setFieldValue") as (field: string, value: unknown) => void;

    setFieldValue("role", role);
    await flushPromises();
};

const selectUsers = async (
    wrapper: Awaited<ReturnType<typeof mountView>>,
    users: unknown[]
) => {
    await wrapper
        .findComponent(UserSearchStub)
        .vm.$emit("user-selection-update", users);
    await flushPromises();
};

describe("AddAppPermission", () => {
    beforeEach(() => {
        createAssignment.mockReset();
        createAssignment.mockResolvedValue({ data: [] });
        push.mockReset();
    });

    it("grants to the users the search reported", async () => {
        const wrapper = await mountView();

        await selectUsers(wrapper, [IDIR_USER]);
        await selectRole(wrapper, ROLE);
        await submit(wrapper);

        expect(createAssignment).toHaveBeenCalledTimes(1);
        expect(createAssignment.mock.calls[0][2]).toMatchObject({
            user_guid: "AAAA1111",
            role_name: "FREP_ADMINISTRATOR",
            target_user_email: "jane@gov.bc.ca",
        });
    });

    it("grants against the domain the search is on", async () => {
        // The domain picks the identity provider CSS assigns against, so a stale
        // value assigns against the wrong one.
        const wrapper = await mountView();

        await wrapper
            .findComponent(UserSearchStub)
            .vm.$emit("user-domain-change", UserType.BceidBus);
        await selectUsers(wrapper, [IDIR_USER]);
        await selectRole(wrapper, ROLE);
        await submit(wrapper);

        expect(createAssignment.mock.calls[0][2]).toMatchObject({
            user_type: UserType.BceidBus,
        });
    });

    it("makes one request per selected user", async () => {
        const wrapper = await mountView();

        await selectUsers(wrapper, [
            IDIR_USER,
            { userId: "BJONES", guid: "BBBB2222", email: "bo@gov.bc.ca" },
        ]);
        await selectRole(wrapper, ROLE);
        await submit(wrapper);

        expect(createAssignment).toHaveBeenCalledTimes(2);
    });

    it("says something when the form is incomplete, rather than doing nothing", async () => {
        // The complaint that started this: pressing the button appeared to have
        // no effect, because the only feedback was a field error at the top of a
        // long form.
        const wrapper = await mountView();

        await submit(wrapper);

        expect(createAssignment).not.toHaveBeenCalled();
        expect(wrapper.text()).toContain("Check the highlighted fields");
    });

    it("does not grant when no role was chosen", async () => {
        const wrapper = await mountView();

        await selectUsers(wrapper, [IDIR_USER]);
        await submit(wrapper);

        expect(createAssignment).not.toHaveBeenCalled();
    });
});
