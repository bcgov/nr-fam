import { VueQueryPlugin } from "@tanstack/vue-query";
import { flushPromises, mount } from "@vue/test-utils";
import PrimeVue from "primevue/config";
import ConfirmationService from "primevue/confirmationservice";
import Toast from "primevue/toast";
import ToastService from "primevue/toastservice";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { defineComponent, h } from "vue";

/**
 * Revoking a permission from the users table.
 *
 * The removal itself was already covered by the service; what was not covered is
 * what a person sees afterwards. A revocation used to say nothing at all: the
 * row disappeared, which on a paginated table can happen off-screen, and there
 * was no other sign it had worked.
 */
/*
    jsdom has no matchMedia, and PrimeVue's Select - the paginator's rows-per-page
    dropdown - binds an orientation listener on mount. Without this the table
    never finishes mounting and every assertion below fails for the wrong reason.
*/
window.matchMedia =
    window.matchMedia ??
    ((query: string) =>
        ({
            matches: false,
            media: query,
            onchange: null,
            addEventListener: () => {},
            removeEventListener: () => {},
            addListener: () => {},
            removeListener: () => {},
            dispatchEvent: () => false,
        }) as unknown as MediaQueryList);

const getAssignments = vi.fn();
const deleteAssignment = vi.fn();

vi.mock("@/services/ApiServiceFactory", () => ({
    AdminMgmtApiService: {
        cssIntegrationsApi: {
            getCssUserRoleAssignments: (...args: unknown[]) =>
                getAssignments(...args),
            deleteCssUserRoleAssignment: (...args: unknown[]) =>
                deleteAssignment(...args),
        },
    },
    AppActlApiService: {},
}));

const push = vi.fn();
vi.mock("vue-router", () => ({
    useRouter: () => ({ push }),
}));

const ROW = {
    username: "JSMITH",
    user_guid: "AABB1122",
    domain: "IDIR",
    first_name: "Jane",
    last_name: "Smith",
    email: "jane@gov.bc.ca",
    role_name: "CHR_FREP_EDITOR",
    role_display_name: "Submitter (CHR)",
    scopes: [{ type: "DISTRICT", value: "DCC", label: "Cariboo-Chilcotin" }],
};

const settle = async () => {
    for (let i = 0; i < 5; i++) {
        await flushPromises();
        await new Promise((resolve) => setTimeout(resolve, 0));
    }
};

const mounted: Array<{ unmount: () => void }> = [];

const mountTable = async () => {
    const CssPermissionsTable = (await import("./CssPermissionsTable.vue"))
        .default;

    // The real Toast beside the table, the way App.vue mounts one above the
    // router view. Without it the toast is handed to the service and never
    // rendered, so a broken chain would look identical to a working one.
    const Harness = defineComponent({
        props: { integrationId: Number, environment: String, appName: String },
        setup: (props) => () =>
            h("div", [h(Toast), h(CssPermissionsTable, props as never)]),
    });

    const wrapper = mount(Harness, {
        props: { integrationId: 22264, environment: "dev", appName: "FREP" },
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

/** Clicks the confirmation's accept button, which teleports out of the table. */
const acceptConfirmation = async () => {
    const accept = Array.from(document.body.querySelectorAll("button")).find(
        (button) => button.textContent?.trim() === "Remove"
    );
    expect(accept, "the confirmation should offer a Remove button").toBeTruthy();
    accept!.click();
    await settle();
};

const revokeFirstRow = async (
    wrapper: Awaited<ReturnType<typeof mountTable>>
) => {
    await wrapper
        .find("button[title='Delete user permission']")
        .trigger("click");
    await settle();
    await acceptConfirmation();
};

describe("CssPermissionsTable", () => {
    beforeEach(() => {
        getAssignments.mockReset().mockResolvedValue({ data: [ROW] });
        deleteAssignment.mockReset().mockResolvedValue({ data: undefined });
    });

    // A toast outlives the screen that raised it by design, so without this a
    // later test reads an earlier one's confirmation and passes on it.
    afterEach(() => {
        mounted.forEach((wrapper) => wrapper.unmount());
        mounted.length = 0;
    });

    it("does not revoke anything until the confirmation is accepted", async () => {
        const wrapper = await mountTable();

        await wrapper
            .find("button[title='Delete user permission']")
            .trigger("click");
        await settle();

        expect(deleteAssignment).not.toHaveBeenCalled();
    });

    it("revokes the role with every one of the row's scopes", async () => {
        const wrapper = await mountTable();
        await revokeFirstRow(wrapper);

        // A compound role revoked with only one of its scopes names a role
        // nobody holds, and the removal quietly does nothing.
        expect(deleteAssignment).toHaveBeenCalledWith(22264, "dev", {
            user_guid: "AABB1122",
            user_type: "IDIR",
            role_name: "CHR_FREP_EDITOR",
            scopes: [{ type: "DISTRICT", values: ["DCC"] }],
        });
    });

    it("confirms the removal with a toast naming role, scope and user", async () => {
        const wrapper = await mountTable();
        await revokeFirstRow(wrapper);

        const toast = wrapper.text() + (document.body.textContent ?? "");
        expect(toast).toContain("Permission removed");
        expect(toast).toContain("Submitter (CHR)");
        expect(toast).toContain("Cariboo-Chilcotin");
        expect(toast).toContain("JSMITH");
    });

    it("raises no toast when the revocation was refused", async () => {
        deleteAssignment.mockRejectedValue({
            response: { data: { description: "You cannot remove your own access." } },
        });

        const wrapper = await mountTable();
        await revokeFirstRow(wrapper);

        // Otherwise the screen says it worked and shows the error at once.
        const text = wrapper.text() + (document.body.textContent ?? "");
        expect(text).not.toContain("Permission removed");
        expect(wrapper.text()).toContain("You cannot remove your own access.");
    });

    it("reloads the table after a revocation", async () => {
        const wrapper = await mountTable();
        const before = getAssignments.mock.calls.length;

        await revokeFirstRow(wrapper);

        // Otherwise the row stays on screen and the table disagrees with CSS.
        expect(getAssignments.mock.calls.length).toBeGreaterThan(before);
    });
});
