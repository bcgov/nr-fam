import { mount } from "@vue/test-utils";
import PrimeVue from "primevue/config";
import { describe, expect, it, vi } from "vitest";
import { defineComponent, h } from "vue";
import { useForm } from "vee-validate";
import { newRoleScopeSelection } from "@/utils/ScopeUtils";

/**
 * Which organisation picker the card shows.
 *
 * Two shapes, chosen by whether the caller is confined to a few organisations.
 * The distinction is null-versus-empty and it is easy to collapse by accident:
 * an empty delegation must still list (empty), not fall back to a search box
 * offering every organisation in the province.
 */
vi.mock("./DistrictSelectTable.vue", () => ({
    default: defineComponent({ name: "DistrictSelectTable", template: "<div />" }),
}));
vi.mock("./ForestClientAddTable.vue", () => ({
    default: defineComponent({ name: "ForestClientAddTable", template: "<div />" }),
}));

const role = (overrides = {}) =>
    ({
        name: "CHR_FREP_VIEWER",
        display_name: "Viewer (CHR)",
        description: null,
        composite: false,
        composites: [],
        role_type_district: false,
        role_type_client: true,
        ...overrides,
    }) as any;

const mountCard = async (roleOverrides = {}) => {
    const RoleScopeCard = (await import("./RoleScopeCard.vue")).default;

    const Harness = defineComponent({
        setup: () => {
            useForm({ initialValues: {} });
            return () =>
                h(RoleScopeCard, {
                    selection: newRoleScopeSelection(role(roleOverrides)) as never,
                    fieldPath: "roles[0]",
                    environment: "dev",
                    setFieldValue: vi.fn(),
                    onRemove: vi.fn(),
                });
        },
    });

    return mount(Harness, { global: { plugins: [PrimeVue] } });
};

const shows = (wrapper: any, name: string) =>
    wrapper.findComponent({ name }).exists();

describe("RoleScopeCard organisation picker", () => {
    it("searches when the caller may grant any organisation", async () => {
        // Null is a FAM or application administrator. A list would be useless
        // for them - the answer is any of tens of thousands.
        const wrapper = await mountCard({ grantable_forest_clients: null });

        expect(shows(wrapper, "ForestClientAddTable")).toBe(true);
        expect(shows(wrapper, "ForestClientSelectTable")).toBe(false);
    });

    it("lists when the caller is confined to a few", async () => {
        // Searching for something you have already been told the whole of is
        // work for no reason.
        const wrapper = await mountCard({
            grantable_forest_clients: [
                { forest_client_number: "00001012", client_name: "ACME LTD." },
            ],
        });

        expect(shows(wrapper, "ForestClientSelectTable")).toBe(true);
        expect(shows(wrapper, "ForestClientAddTable")).toBe(false);
    });

    it("still lists when the delegation names none", async () => {
        // The trap: empty is not "unrestricted". Falling back to the search box
        // here would offer every organisation to the one person who may grant
        // none of them.
        const wrapper = await mountCard({ grantable_forest_clients: [] });

        expect(shows(wrapper, "ForestClientSelectTable")).toBe(true);
        expect(shows(wrapper, "ForestClientAddTable")).toBe(false);
    });

    it("shows no organisation picker at all for a district-only role", async () => {
        const wrapper = await mountCard({
            role_type_client: false,
            role_type_district: true,
        });

        expect(shows(wrapper, "ForestClientSelectTable")).toBe(false);
        expect(shows(wrapper, "ForestClientAddTable")).toBe(false);
        expect(shows(wrapper, "DistrictSelectTable")).toBe(true);
    });
});
