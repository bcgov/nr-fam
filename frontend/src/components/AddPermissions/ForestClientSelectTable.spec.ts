import { mount } from "@vue/test-utils";
import PrimeVue from "primevue/config";
import { describe, expect, it, vi } from "vitest";
import { defineComponent, h } from "vue";
import { useForm } from "vee-validate";

/**
 * The organisation picker a delegated administrator gets.
 *
 * A list rather than a search box: their delegation names a handful, and
 * searching for something you have already been told the whole of is work for
 * no reason. Same shape as the district picker, for the same reason.
 */
const client = (number: string, name: string) => ({
    forest_client_number: number,
    client_name: name,
    status: { status_code: "A", description: "Active" },
});

const ACME = client("00001012", "ACME LTD.");
const BEECH = client("00001013", "BEECH HOLDINGS");

const setFieldValue = vi.fn();

/** vee-validate needs a form in scope for `useField`. */
const mountPicker = async (options: unknown[], selected: unknown[] = []) => {
    const ForestClientSelectTable = (
        await import("./ForestClientSelectTable.vue")
    ).default;

    const Harness = defineComponent({
        setup: () => {
            useForm({ initialValues: { "roles[0].forestClients": [] } });
            return () =>
                h(ForestClientSelectTable, {
                    fieldId: "roles[0].forestClients",
                    selected: selected as never,
                    options: options as never,
                    setFieldValue,
                });
        },
    });

    return mount(Harness, { global: { plugins: [PrimeVue] } });
};

describe("ForestClientSelectTable", () => {
    it("lists exactly the organisations it was given", async () => {
        const wrapper = await mountPicker([ACME, BEECH]);

        expect(wrapper.text()).toContain("ACME LTD.");
        expect(wrapper.text()).toContain("BEECH HOLDINGS");
        // The number as well as the name: the number is what the grant carries,
        // and what somebody would be checking against a ticket.
        expect(wrapper.text()).toContain("00001012");
    });

    it("says so when the delegation names none", async () => {
        // An empty table with no explanation reads as a screen that failed to
        // load rather than a delegation that covers nothing.
        const wrapper = await mountPicker([]);

        expect(wrapper.text()).toContain(
            "not been delegated any organization"
        );
    });

    it("adds an organisation when it is ticked", async () => {
        setFieldValue.mockReset();
        const wrapper = await mountPicker([ACME, BEECH]);

        await wrapper.findAll(".p-checkbox input")[1].trigger("change");

        expect(setFieldValue).toHaveBeenCalledWith("roles[0].forestClients", [
            BEECH,
        ]);
    });

    it("removes one that was already chosen", async () => {
        setFieldValue.mockReset();
        const wrapper = await mountPicker([ACME, BEECH], [ACME]);

        await wrapper.findAll(".p-checkbox input")[0].trigger("change");

        expect(setFieldValue).toHaveBeenCalledWith("roles[0].forestClients", []);
    });

    it("shows a ticked box for one already chosen", async () => {
        const wrapper = await mountPicker([ACME, BEECH], [ACME]);

        // Without this a reopened form looks empty while the values are set.
        expect(
            wrapper.findAllComponents({ name: "Checkbox" })[0].props("modelValue")
        ).toBe(true);
    });
});
