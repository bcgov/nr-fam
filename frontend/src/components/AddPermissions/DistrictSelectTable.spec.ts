import { VueQueryPlugin } from "@tanstack/vue-query";
import { flushPromises, mount } from "@vue/test-utils";
import PrimeVue from "primevue/config";
import { beforeEach, describe, expect, it, vi } from "vitest";

/**
 * Which districts the picker offers.
 *
 * A delegated administrator's delegation names concrete districts, and the
 * grant path compares the scoped role name - so a district outside it is
 * refused however it was chosen. The picker was offering all of them, which
 * made the form promise grants that could not happen.
 */
const getDistricts = vi.fn();

vi.mock("@/services/ApiServiceFactory", () => ({
    AppActlApiService: { districtsApi: { getDistricts: () => getDistricts() } },
    AdminMgmtApiService: {},
}));

const DISTRICTS = [
    { org_unit_code: "DCC", org_unit_name: "Cariboo-Chilcotin", expired: false },
    { org_unit_code: "DKA", org_unit_name: "Thompson Rivers", expired: false },
    { org_unit_code: "DQU", org_unit_name: "Quesnel", expired: false },
    { org_unit_code: "DOLD", org_unit_name: "Dissolved", expired: true },
];

const mountPicker = async (allowed?: string[] | null) => {
    const DistrictSelectTable = (
        await import("./DistrictSelectTable.vue")
    ).default;

    const wrapper = mount(DistrictSelectTable, {
        props: {
            fieldId: "roles[0].districts",
            selected: [],
            setFieldValue: vi.fn(),
            ...(allowed === undefined ? {} : { allowed }),
        },
        global: { plugins: [VueQueryPlugin, PrimeVue] },
    });

    for (let i = 0; i < 5; i++) {
        await flushPromises();
        await new Promise((resolve) => setTimeout(resolve, 0));
    }
    return wrapper;
};

const codesShown = (wrapper: { text: () => string }) =>
    DISTRICTS.filter((d) => wrapper.text().includes(d.org_unit_name)).map(
        (d) => d.org_unit_code
    );

describe("DistrictSelectTable", () => {
    beforeEach(() => {
        getDistricts.mockReset().mockResolvedValue({ data: DISTRICTS });
    });

    it("offers every active district when nothing restricts the caller", async () => {
        // Null is a FAM or application administrator. Filtering them would be
        // as wrong as not filtering a delegated one.
        const wrapper = await mountPicker(null);

        expect(codesShown(wrapper)).toEqual(["DCC", "DKA", "DQU"]);
    });

    it("offers only the districts the delegation names", async () => {
        const wrapper = await mountPicker(["DCC", "DQU"]);

        expect(codesShown(wrapper)).toEqual(["DCC", "DQU"]);
    });

    it("still hides an expired district inside the allowed set", async () => {
        // Expired districts cannot be granted at all; being delegated one does
        // not change that.
        const wrapper = await mountPicker(["DCC", "DOLD"]);

        expect(codesShown(wrapper)).toEqual(["DCC"]);
    });

    it("offers nothing when the delegation names no district", async () => {
        // Empty is not "unrestricted". Reading it that way would put every
        // district in front of somebody who may grant none.
        const wrapper = await mountPicker([]);

        expect(codesShown(wrapper)).toEqual([]);
        expect(wrapper.text()).toContain("not been delegated any district");
    });
});
