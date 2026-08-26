import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";

/**
 * Granting from a CSV.
 *
 * The point of the screen is the step between upload and grant: the file is
 * shown back as <b>names</b> before anything happens, because a table of GUIDs
 * and role codes is not something anybody can check by eye - confirming one
 * would be theatre.
 */

const previewCssBulkGrants = vi.fn();
const createCssBulkGrants = vi.fn();
const navigate = vi.fn();

vi.mock("@/services/ApiServiceFactory", () => ({
    AdminMgmtApiService: {
        cssIntegrationsApi: {
            previewCssBulkGrants: (
                integrationId: number,
                environment: string,
                csv: string
            ) => previewCssBulkGrants(integrationId, environment, csv),
            createCssBulkGrants: (
                integrationId: number,
                environment: string,
                csv: string
            ) => createCssBulkGrants(integrationId, environment, csv),
        },
    },
    AppActlApiService: {},
}));

vi.mock("react-router-dom", async () => {
    const actual = await vi.importActual<typeof import("react-router-dom")>(
        "react-router-dom"
    );
    return { ...actual, useNavigate: () => navigate };
});

const { BulkGrant } = await import("./index");
const { SelectedAppProvider } = await import(
    "@/context/application/SelectedAppProvider"
);

const GOOD_ROW = {
    line_number: 2,
    user_guid: "AAAA1111",
    user_name: "JSMITH",
    user_type: "IDIR",
    first_name: "Jane",
    last_name: "Smith",
    role_code: "FREP_EDITOR",
    role_display_name: "Editor",
    district: "DCC",
    district_name: "Cariboo-Chilcotin",
    valid: true,
};

const BAD_ROW = {
    line_number: 3,
    user_guid: "BBBB2222",
    role_code: "NOPE",
    valid: false,
    error: "No role named NOPE",
};

const CSV = "user_guid,user_type,role,district,organization\nAAAA1111,IDIR,FREP_EDITOR,DCC,\n";

const renderPage = () => {
    const queryClient = new QueryClient({
        defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    });
    render(
        <QueryClientProvider client={queryClient}>
            <MemoryRouter
                initialEntries={[
                    "/manage-permissions/bulk-upload?integrationId=6538&environment=dev",
                ]}
            >
                <SelectedAppProvider>
                    <Routes>
                        <Route
                            path="/manage-permissions/bulk-upload"
                            element={<BulkGrant />}
                        />
                    </Routes>
                </SelectedAppProvider>
            </MemoryRouter>
        </QueryClientProvider>
    );
};

/** Drives the real file input, which is what the drop zone and dialog both use. */
const upload = async (contents = CSV) => {
    const file = new File([contents], "grants.csv", { type: "text/csv" });
    const input = document.querySelector(
        'input[type="file"]'
    ) as HTMLInputElement;
    await userEvent.upload(input, file);
};

describe("BulkGrant", () => {
    beforeEach(() => {
        previewCssBulkGrants
            .mockReset()
            .mockResolvedValue({ data: { rows: [GOOD_ROW, BAD_ROW] } });
        createCssBulkGrants.mockReset().mockResolvedValue({
            data: [{ ...GOOD_ROW }, BAD_ROW],
        });
        navigate.mockReset();
    });

    it("shows the file's shape before anything is uploaded", async () => {
        renderPage();

        // The header row is on screen beside the download, so nobody has to open
        // the template to learn what the columns are.
        expect(screen.getByText(/user_guid,user_type,role/)).toBeInTheDocument();
        expect(screen.queryByRole("table")).not.toBeInTheDocument();
    });

    it("previews the file rather than granting from it", async () => {
        renderPage();

        await upload();

        await waitFor(() => expect(previewCssBulkGrants).toHaveBeenCalled());
        expect(previewCssBulkGrants.mock.calls[0][0]).toBe(6538);
        expect(previewCssBulkGrants.mock.calls[0][1]).toBe("dev");
        expect(previewCssBulkGrants.mock.calls[0][2]).toBe(CSV);
        // Nothing granted yet - that is the whole point of the step.
        expect(createCssBulkGrants).not.toHaveBeenCalled();
    });

    it("shows names and role names, not GUIDs and codes", async () => {
        renderPage();
        await upload();

        const row = (await screen.findByText("Jane Smith")).closest(
            "tr"
        ) as HTMLElement;
        expect(within(row).getByText("Editor")).toBeInTheDocument();
        // The district resolved, with its code kept beside it.
        expect(within(row).getByText(/Cariboo-Chilcotin/)).toBeInTheDocument();
        expect(within(row).getByText("(DCC)")).toBeInTheDocument();
    });

    it("shows the raw GUID when it resolved to nobody", async () => {
        // A blank cell would make an unresolvable row look unremarkable.
        renderPage();
        await upload();

        const row = (await screen.findByText("No role named NOPE")).closest(
            "tr"
        ) as HTMLElement;
        expect(within(row).getByText("BBBB2222")).toBeInTheDocument();
    });

    it("counts what will be granted and what will be skipped", async () => {
        renderPage();
        await upload();

        expect(
            await screen.findByText(/1 row\(s\) will be granted/)
        ).toBeInTheDocument();
        expect(
            screen.getByText(/1 row\(s\) cannot be and will be skipped/)
        ).toBeInTheDocument();
    });

    it("offers to grant only the rows that can be", async () => {
        renderPage();
        await upload();

        expect(
            await screen.findByRole("button", { name: "Grant 1 permission(s)" })
        ).toBeInTheDocument();
    });

    it("will not grant when no row is valid", async () => {
        previewCssBulkGrants.mockResolvedValue({ data: { rows: [BAD_ROW] } });
        renderPage();
        await upload();

        expect(
            await screen.findByRole("button", { name: "Grant 0 permission(s)" })
        ).toBeDisabled();
    });

    it("grants from the same file it previewed", async () => {
        renderPage();
        await upload();

        await userEvent.click(
            await screen.findByRole("button", { name: "Grant 1 permission(s)" })
        );

        await waitFor(() => expect(createCssBulkGrants).toHaveBeenCalled());
        expect(createCssBulkGrants.mock.calls[0][2]).toBe(CSV);
    });

    it("turns the table into outcomes once granting has run", async () => {
        renderPage();
        await upload();
        await userEvent.click(
            await screen.findByRole("button", { name: "Grant 1 permission(s)" })
        );

        // "Ready" was a promise; "Granted" is a report, and the heading says so.
        expect(await screen.findByText("Granted")).toBeInTheDocument();
        expect(screen.getByText(/1 of 2 row\(s\) granted/)).toBeInTheDocument();
        expect(
            screen.getByRole("button", { name: "Done" })
        ).toBeInTheDocument();
    });

    it("reports a whole-file refusal", async () => {
        // Empty, or too many rows - reported here rather than per row.
        previewCssBulkGrants.mockRejectedValue({
            response: { data: { description: "The file has no rows." } },
        });
        renderPage();

        await upload();

        expect(
            await screen.findByText("The file has no rows.")
        ).toBeInTheDocument();
        expect(screen.queryByRole("table")).not.toBeInTheDocument();
    });

    it("clears the preview when the file is removed", async () => {
        renderPage();
        await upload();
        await screen.findByText("Jane Smith");

        await userEvent.click(
            screen.getByRole("button", { name: /remove/i })
        );

        expect(screen.queryByText("Jane Smith")).not.toBeInTheDocument();
        expect(screen.queryByRole("table")).not.toBeInTheDocument();
    });

    it("re-previews when a second file is chosen", async () => {
        // The preview belongs to the file, not to the screen.
        renderPage();
        await upload();
        await waitFor(() => expect(previewCssBulkGrants).toHaveBeenCalledTimes(1));

        await upload("user_guid,user_type,role,district,organization\nCCCC,IDIR,X,,\n");

        await waitFor(() => expect(previewCssBulkGrants).toHaveBeenCalledTimes(2));
        expect(previewCssBulkGrants.mock.calls[1][2]).toContain("CCCC");
    });

    it("clears the old preview while the new file is being checked", async () => {
        // Otherwise the first file's rows sit there under the second file's
        // name, reading as though they had been checked.
        renderPage();
        await upload();
        await screen.findByText("Jane Smith");

        let release: (value: unknown) => void = () => {};
        previewCssBulkGrants.mockReturnValue(
            new Promise((resolve) => {
                release = resolve;
            })
        );

        await upload("user_guid,user_type,role,district,organization\nCCCC,IDIR,X,,\n");

        await waitFor(() =>
            expect(screen.queryByText("Jane Smith")).not.toBeInTheDocument()
        );
        release({ data: { rows: [] } });
    });
});
