import { VueQueryPlugin } from "@tanstack/vue-query";
import { flushPromises, mount } from "@vue/test-utils";
import { beforeEach, describe, expect, it, vi } from "vitest";

/**
 * Bulk upload.
 *
 * The two things that must hold: nothing is granted until the person confirms,
 * and what they confirm is names and role names rather than the GUIDs and codes
 * the file contains. A confirmation of identifiers nobody can check by eye would
 * be worse than no confirmation at all.
 */
const preview = vi.fn();
const apply = vi.fn();

const downloadTemplate = vi.fn();
vi.mock("./utils", async () => ({
    ...(await vi.importActual<typeof import("./utils")>("./utils")),
    downloadTemplateCsv: () => downloadTemplate(),
}));

vi.mock("@/services/ApiServiceFactory", () => ({
    AdminMgmtApiService: {
        cssIntegrationsApi: {
            previewCssBulkGrants: (...args: unknown[]) => preview(...args),
            createCssBulkGrants: (...args: unknown[]) => apply(...args),
        },
    },
    AppActlApiService: {},
}));

const push = vi.fn();
vi.mock("vue-router", () => ({ useRouter: () => ({ push }) }));

const VALID_ROW = {
    line_number: 2,
    user_guid: "AABBCCDDEEFF00112233445566778899",
    role_code: "FSPTS_VIEW_ALL",
    user_type: "IDIR",
    user_name: "JANES",
    first_name: "Jane",
    last_name: "Smith",
    email: "jane@gov.bc.ca",
    organization: null,
    role_display_name: "View All",
    valid: true,
    error: null,
};

const INVALID_ROW = {
    ...VALID_ROW,
    line_number: 3,
    user_guid: "DEADBEEF",
    first_name: null,
    last_name: null,
    user_name: null,
    role_display_name: null,
    valid: false,
    error: "No IDIR or Business BCeID user has this GUID.",
};

const settle = async () => {
    for (let i = 0; i < 5; i++) {
        await flushPromises();
        await new Promise((resolve) => setTimeout(resolve, 0));
    }
};

const mountView = async () => {
    const BulkGrant = (await import("./index.vue")).default;
    return mount(BulkGrant, {
        props: { integrationId: 54321, environment: "dev" },
        global: {
            plugins: [
                [
                    VueQueryPlugin,
                    {
                        queryClientConfig: {
                            defaultOptions: { queries: { retry: false } },
                        },
                    },
                ],
            ],
            stubs: {
                PageTitle: true,
                StepContainer: { template: "<div><slot /></div>" },
                Button: {
                    props: ["label", "disabled"],
                    template:
                        "<button :disabled=\"disabled\" @click=\"$emit('click')\">{{ label }}</button>",
                },
            },
        },
    });
};

/** Drives the file input the way choosing a file does. */
const chooseFile = async (wrapper: any, text: string) => {
    const input = wrapper.find('input[type="file"]');
    const file = { name: "grants.csv", text: () => Promise.resolve(text) };
    Object.defineProperty(input.element, "files", { value: [file], configurable: true });
    await input.trigger("change");
    await settle();
};

const clickButton = async (wrapper: any, match: string) => {
    const button = wrapper
        .findAll("button")
        .find((b: any) => b.text().includes(match));
    expect(button, `expected a button matching "${match}"`).toBeTruthy();
    await button.trigger("click");
    await settle();
};

/** Drops a file on the zone the way a drag-and-drop does. */
const dropFile = async (wrapper: any, text: string) => {
    const file = { name: "dropped.csv", text: () => Promise.resolve(text) };
    await wrapper.find(".dnd-zone").trigger("drop", {
        dataTransfer: { files: [file] },
    });
    await settle();
};

describe("BulkGrant", () => {
    beforeEach(() => {
        preview.mockReset().mockResolvedValue({
            data: { rows: [VALID_ROW], valid_count: 1, error_count: 0 },
        });
        apply.mockReset().mockResolvedValue({ data: [VALID_ROW] });
        push.mockReset();
        downloadTemplate.mockReset();
    });

    it("offers the template as a link in the instructions", async () => {
        // The template used to be an outlined button below the example. It is a
        // link inside the sentence now, so the wiring is worth holding onto:
        // silently losing it would leave people to invent the format.
        const wrapper = await mountView();
        const link = wrapper.find(".template-link");

        expect(link.exists()).toBe(true);
        expect(link.text()).toContain("Download the template");

        await link.trigger("click");
        expect(downloadTemplate).toHaveBeenCalled();
    });

    it("previews the chosen file without granting anything", async () => {
        const wrapper = await mountView();
        await chooseFile(wrapper, "guid,role\nAABB,FSPTS_VIEW_ALL");

        expect(preview).toHaveBeenCalledWith(
            54321,
            "dev",
            "guid,role\nAABB,FSPTS_VIEW_ALL"
        );
        // The whole point of the two steps.
        expect(apply).not.toHaveBeenCalled();
    });

    it("confirms with names and role names, not GUIDs and codes", async () => {
        const wrapper = await mountView();
        await chooseFile(wrapper, "csv");

        expect(wrapper.text()).toContain("Jane Smith");
        expect(wrapper.text()).toContain("View All");
    });

    it("grants only when the confirmation is clicked", async () => {
        const wrapper = await mountView();
        await chooseFile(wrapper, "csv");
        expect(apply).not.toHaveBeenCalled();

        await clickButton(wrapper, "Grant");
        expect(apply).toHaveBeenCalledWith(54321, "dev", "csv");
    });

    it("shows why a row cannot be granted, rather than hiding it", async () => {
        preview.mockResolvedValue({
            data: { rows: [VALID_ROW, INVALID_ROW], valid_count: 1, error_count: 1 },
        });

        const wrapper = await mountView();
        await chooseFile(wrapper, "csv");

        // Hiding it would let somebody submit believing the whole file applied.
        expect(wrapper.text()).toContain("No IDIR or Business BCeID user");
        expect(wrapper.text()).toContain("1 row(s) will be granted");
    });

    it("falls back to the GUID when a row resolved to nobody", async () => {
        preview.mockResolvedValue({
            data: { rows: [INVALID_ROW], valid_count: 0, error_count: 1 },
        });

        const wrapper = await mountView();
        await chooseFile(wrapper, "csv");

        expect(wrapper.text()).toContain("DEADBEEF");
    });

    it("offers nothing to grant when every row is invalid", async () => {
        preview.mockResolvedValue({
            data: { rows: [INVALID_ROW], valid_count: 0, error_count: 1 },
        });

        const wrapper = await mountView();
        await chooseFile(wrapper, "csv");

        const grant = wrapper
            .findAll("button")
            .find((b: any) => b.text().includes("Grant"));
        expect(grant?.attributes("disabled")).toBeDefined();
    });

    it("reports a whole-file refusal", async () => {
        preview.mockRejectedValue({
            response: {
                data: {
                    description:
                        "The file has 300 rows; the most that can be uploaded at once is 200.",
                },
            },
        });

        const wrapper = await mountView();
        await chooseFile(wrapper, "csv");

        expect(wrapper.text()).toContain("most that can be uploaded");
    });

    it("accepts a dropped file exactly as a chosen one", async () => {
        // The drop zone is the visible control; a file arriving that way must
        // take the same path, preview included, or dragging would look like it
        // worked and do nothing.
        const wrapper = await mountView();
        await dropFile(wrapper, "guid,role\nAABB,FSPTS_VIEW_ALL");

        expect(preview).toHaveBeenCalledWith(
            54321,
            "dev",
            "guid,role\nAABB,FSPTS_VIEW_ALL"
        );
        expect(wrapper.text()).toContain("dropped.csv");
    });

    it("highlights the zone while a file is over it", async () => {
        const wrapper = await mountView();
        const zone = wrapper.find(".dnd-zone");

        await zone.trigger("dragover");
        expect(zone.classes()).toContain("dnd-zone--drag");

        await zone.trigger("dragleave");
        expect(zone.classes()).not.toContain("dnd-zone--drag");
    });

    it("removing the file clears the preview with it", async () => {
        // Leaving the parsed rows on screen after the file is gone would offer a
        // Grant button for a file the person just removed.
        const wrapper = await mountView();
        await chooseFile(wrapper, "csv");
        expect(wrapper.text()).toContain("Jane Smith");

        await wrapper.find(".file-chip__remove").trigger("click");
        await settle();

        expect(wrapper.text()).not.toContain("Jane Smith");
        expect(wrapper.text()).not.toContain("grants.csv");
    });

    it("shows outcomes after granting", async () => {
        const wrapper = await mountView();
        await chooseFile(wrapper, "csv");
        await clickButton(wrapper, "Grant");

        expect(wrapper.text()).toContain("1 of 1 row(s) granted");
    });
});
