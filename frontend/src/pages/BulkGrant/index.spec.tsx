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
const { NotificationProvider } = await import(
    "@/context/notification/NotificationProvider"
);
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

/*
    No user_guid: the file names people by username, and the backend resolves the
    GUID from it - so a row that resolved to nobody has no GUID to show, only the
    name the file used.
*/
const BAD_ROW = {
    line_number: 3,
    user_name: "NOBODY",
    role_code: "NOPE",
    valid: false,
    error: "No role named NOPE",
};

const CSV = "username,user_type,role,district,organization\nJSMITH,IDIR,FREP_EDITOR,DCC,\n";

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
                {/* The page raises a toast on success, as the app's shell provides. */}
                <NotificationProvider>
                    <SelectedAppProvider>
                        <Routes>
                            <Route
                                path="/manage-permissions/bulk-upload"
                                element={<BulkGrant />}
                            />
                        </Routes>
                    </SelectedAppProvider>
                </NotificationProvider>
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
        expect(screen.getByText(/username,user_type,role/)).toBeInTheDocument();
        expect(screen.queryByRole("table")).not.toBeInTheDocument();
    });

    /**
     * The screen's shape, ported from nr-fsp-new's data submission page: an
     * "Upload" section on a full-width gray canvas, the file area alone on a
     * white card, and no rules anywhere.
     */
    describe("the shape of the screen", () => {
        it("puts the upload on a white card over a gray canvas", async () => {
            renderPage();

            expect(
                screen.getByRole("heading", { name: "Upload", level: 2 })
            ).toBeInTheDocument();
            expect(document.querySelector(".bulk-grant-canvas")).not.toBeNull();
            expect(document.querySelector(".bulk-grant-card")).not.toBeNull();
            // The card groups the area, so the rules are gone.
            expect(document.querySelector(".step-container__divider")).toBeNull();
        });

        it("keeps the template above the upload pane rather than inside it", async () => {
            // Reference material, read once and skipped on every later visit.
            // Inside the pane it read as the first step of the upload.
            renderPage();

            const template = document.querySelector(".bulk-grant-template");
            const canvas = document.querySelector(".bulk-grant-canvas");
            expect(template).not.toBeNull();
            expect(canvas?.contains(template!)).toBe(false);
            // And it comes first on the page.
            expect(
                template!.compareDocumentPosition(canvas!) &
                    Node.DOCUMENT_POSITION_FOLLOWING
            ).toBeTruthy();
        });

        it("labels the file area the way FSP labels its own", async () => {
            renderPage();

            const card = document.querySelector(".bulk-grant-card") as HTMLElement;
            expect(within(card).getByText("Permissions file")).toBeInTheDocument();
            expect(
                within(card).getByText(/Accepted format: CSV/)
            ).toBeInTheDocument();
            // Only the file area is on the card now.
            expect(within(card).queryByText("Template")).not.toBeInTheDocument();
        });
    });

    /**
     * What the screen does while it is working, and where it puts you after.
     *
     * <p>Checking a file is a directory lookup per row against a slow upstream,
     * so two hundred rows is a wait measured in seconds - and the confirmation
     * it produces lands below the fold.
     */
    describe("while the file is being checked", () => {
        it("says it is working rather than sitting still", async () => {
            // A file chip appearing and then nothing happening reads as an
            // upload that failed silently.
            let release: (value: unknown) => void = () => {};
            previewCssBulkGrants.mockReturnValue(
                new Promise((resolve) => {
                    release = resolve;
                })
            );

            renderPage();
            await upload();

            expect(
                await screen.findByText(/Checking grants\.csv/)
            ).toBeInTheDocument();

            release({ data: { rows: [GOOD_ROW] } });
            await waitFor(() =>
                expect(screen.queryByText(/Checking grants\.csv/)).not.toBeInTheDocument()
            );
        });

        it("stops saying so once the rows are in", async () => {
            renderPage();
            await upload();

            await screen.findByText("Ready");
            expect(screen.queryByText(/Checking /)).not.toBeInTheDocument();
        });
    });

    describe("when the confirmation appears", () => {
        it("brings it into view, since it lands below the fold", async () => {
            const scrollIntoView = vi.fn();
            const original = Element.prototype.scrollIntoView;
            Element.prototype.scrollIntoView = scrollIntoView;

            try {
                renderPage();
                await upload();
                await screen.findByText("Ready");

                await waitFor(() => expect(scrollIntoView).toHaveBeenCalled());
                expect(scrollIntoView.mock.calls[0][0]).toMatchObject({
                    behavior: "smooth",
                    block: "start",
                });
            } finally {
                Element.prototype.scrollIntoView = original;
            }
        });

        it("does not animate for somebody who asked it not to", async () => {
            // A page that moves under the cursor is the whole point of that
            // setting; it still goes there, just without the travel.
            const scrollIntoView = vi.fn();
            const original = Element.prototype.scrollIntoView;
            const matchMedia = window.matchMedia;
            Element.prototype.scrollIntoView = scrollIntoView;
            window.matchMedia = ((query: string) => ({
                matches: query.includes("prefers-reduced-motion"),
                media: query,
                addEventListener: () => {},
                removeEventListener: () => {},
                addListener: () => {},
                removeListener: () => {},
                onchange: null,
                dispatchEvent: () => false,
            })) as typeof window.matchMedia;

            try {
                renderPage();
                await upload();
                await screen.findByText("Ready");

                await waitFor(() => expect(scrollIntoView).toHaveBeenCalled());
                expect(scrollIntoView.mock.calls[0][0]).toMatchObject({
                    behavior: "auto",
                });
            } finally {
                Element.prototype.scrollIntoView = original;
                window.matchMedia = matchMedia;
            }
        });

        it("goes there once, not again when the outcomes replace the preview", async () => {
            /*
                Granting swaps the preview rows for outcome rows. Moving the page
                a second time would pull it out from under somebody reading the
                result they just asked for.
            */
            const scrollIntoView = vi.fn();
            const original = Element.prototype.scrollIntoView;
            Element.prototype.scrollIntoView = scrollIntoView;

            try {
                renderPage();
                await upload();
                await screen.findByText("Ready");
                await waitFor(() => expect(scrollIntoView).toHaveBeenCalledTimes(1));

                await userEvent.click(
                    screen.getByRole("button", { name: /^Grant / })
                );
                await screen.findByText("Granted");

                expect(scrollIntoView).toHaveBeenCalledTimes(1);
            } finally {
                Element.prototype.scrollIntoView = original;
            }
        });
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

    it("shows the username from the file when it resolved to nobody", async () => {
        // A blank cell would make an unresolvable row look unremarkable - and
        // the username is the one thing that finds the line in the spreadsheet.
        renderPage();
        await upload();

        const row = (await screen.findByText("No role named NOPE")).closest(
            "tr"
        ) as HTMLElement;
        expect(within(row).getAllByText("NOBODY").length).toBeGreaterThan(0);
    });

    describe("the scope columns", () => {
        it("gives district, region and organization a column each", async () => {
            // The file has a column each, so a row that is wrong is wrong in a
            // particular one - and the table now says which.
            renderPage();
            await upload();

            const headers = (await screen.findAllByRole("columnheader")).map(
                (h) => h.textContent
            );
            expect(headers).toContain("District");
            expect(headers).toContain("Region");
            expect(headers).toContain("Organization");
            expect(headers).not.toContain("Scope");
            // The BCeID user's own company, which is a different thing.
            expect(headers).toContain("Business");
        });

        it("shows the resolved name with its code beside it", async () => {
            renderPage();
            await upload();

            const row = (await screen.findByText("Ready")).closest(
                "tr"
            ) as HTMLElement;
            expect(within(row).getByText(/Cariboo-Chilcotin/)).toBeInTheDocument();
            expect(within(row).getByText("(DCC)")).toBeInTheDocument();
        });

        it("dashes the scopes a row does not use", async () => {
            // A column each, and a dash where the row carries nothing - the same
            // placeholder every other table uses for an absent value.
            previewCssBulkGrants.mockResolvedValue({
                data: {
                    rows: [
                        {
                            ...GOOD_ROW,
                            district: undefined,
                            district_name: undefined,
                        },
                    ],
                },
            });

            renderPage();
            await upload();

            const row = (await screen.findByText("Ready")).closest(
                "tr"
            ) as HTMLElement;

            // By column, not by counting dashes: the Business column dashes too
            // for an IDIR user, and a count would pass for the wrong reason.
            const headers = screen
                .getAllByRole("columnheader")
                .map((header) => header.textContent);
            const cells = within(row).getAllByRole("cell");
            for (const column of ["District", "Region", "Organization"]) {
                expect(cells[headers.indexOf(column)]).toHaveTextContent("—");
            }
        });
    });

    describe("dropping a row before granting", () => {
        it("takes the row off the table", async () => {
            // A file is often nearly right - one person who has left - and
            // re-editing a spreadsheet to grant the other forty-nine is a poor
            // answer to that.
            renderPage();
            await upload();
            await screen.findByText("Ready");

            await userEvent.click(
                screen.getByRole("button", { name: /Remove line 2/ })
            );

            expect(screen.queryByText("Ready")).not.toBeInTheDocument();
            // The row that was not removed stays.
            expect(screen.getByText("No role named NOPE")).toBeInTheDocument();
        });

        /*
            Two grantable rows, so removing one still leaves something to grant -
            with only one, Grant is correctly disabled and the file never goes.
        */
        const TWO_ROWS =
            "username,user_type,role,district,organization\n" +
            "JSMITH,IDIR,FREP_EDITOR,DCC,\n" +
            "BLEE,IDIR,FREP_EDITOR,DKA,\n";

        const previewOfTwo = () =>
            previewCssBulkGrants.mockResolvedValue({
                data: {
                    rows: [
                        GOOD_ROW,
                        { ...GOOD_ROW, line_number: 3, user_name: "BLEE" },
                    ],
                },
            });

        it("takes it out of the file that is sent, not just off the screen", async () => {
            /*
                The apply re-reads the text rather than trusting the preview that
                came back through the browser. A removal that only changed the
                table would grant the row anyway - silently, and with the screen
                saying it had not.
            */
            previewOfTwo();

            renderPage();
            await upload(TWO_ROWS);
            await screen.findAllByText("Ready");

            await userEvent.click(
                screen.getByRole("button", { name: /Remove line 2/ })
            );
            await userEvent.click(
                screen.getByRole("button", { name: /^Grant / })
            );

            await waitFor(() => expect(createCssBulkGrants).toHaveBeenCalled());
            const sent = createCssBulkGrants.mock.calls[0][2] as string;
            expect(sent).not.toContain("JSMITH");
            // And the row that was kept is still in it.
            expect(sent).toContain("BLEE");
        });

        it("leaves every other row's line number alone", async () => {
            /*
                The line is blanked rather than deleted. The parser numbers rows
                by their position and skips empty lines, so the numbers the table
                is showing stay true - deleting would renumber the rest of the
                file under the reader.
            */
            previewOfTwo();

            renderPage();
            await upload(TWO_ROWS);
            await screen.findAllByText("Ready");

            await userEvent.click(
                screen.getByRole("button", { name: /Remove line 2/ })
            );
            await userEvent.click(
                screen.getByRole("button", { name: /^Grant / })
            );

            await waitFor(() => expect(createCssBulkGrants).toHaveBeenCalled());
            const lines = (createCssBulkGrants.mock.calls[0][2] as string).split("\n");
            expect(lines[1]).toBe("");
            // BLEE was on line 3 before the removal and is still on line 3.
            expect(lines[2]).toContain("BLEE");
        });

        it("offers no removal once the grant has run", async () => {
            // The column is a report by then, and removing a line from a report
            // takes away the record rather than the access.
            renderPage();
            await upload();
            await userEvent.click(
                await screen.findByRole("button", { name: /^Grant / })
            );
            await screen.findByText("Granted");

            expect(
                screen.queryByRole("button", { name: /Remove line/ })
            ).not.toBeInTheDocument();
        });
    });

    describe("how a row reads", () => {
        it("shows the role as a pill, by name", async () => {
            renderPage();
            await upload();

            const row = (await screen.findByText("Ready")).closest(
                "tr"
            ) as HTMLElement;
            const role = within(row).getByText("Editor");
            expect(role).toBeInTheDocument();
            expect(role.closest(".cds--tag")).not.toBeNull();
            // The code from the file is not what the reader is asked to check.
            expect(within(row).queryByText("FREP_EDITOR")).not.toBeInTheDocument();
        });

        it("shows a status as a pill and an error as words", async () => {
            /*
                A status is one word. An error is a sentence naming what is wrong
                with the row, and a pill would either truncate it or stretch into
                a paragraph with a border round it.
            */
            renderPage();
            await upload();

            const ready = await screen.findByText("Ready");
            expect(ready.closest(".cds--tag")).not.toBeNull();

            const error = screen.getByText("No role named NOPE");
            expect(error.closest(".cds--tag")).toBeNull();
        });

        it("leaves a row that will not grant showing its code, unpilled", async () => {
            // There may be no such role, so there is no name to give it - and a
            // pill would dress up something that is not going to happen.
            renderPage();
            await upload();

            const row = (await screen.findByText("No role named NOPE")).closest(
                "tr"
            ) as HTMLElement;
            const code = within(row).getByText("NOPE");
            expect(code.closest(".cds--tag")).toBeNull();
        });
    });

    describe("a row the person already holds", () => {
        const HELD_ROW = {
            ...GOOD_ROW,
            line_number: 4,
            valid: false,
            already_granted: true,
            error: undefined,
        };

        it("reads as neither a success nor a fault", async () => {
            // Nothing succeeded and nothing went wrong: the person has it.
            previewCssBulkGrants.mockResolvedValue({
                data: { rows: [GOOD_ROW, HELD_ROW] },
            });

            renderPage();
            await upload();

            const pill = await screen.findByText("Already granted");
            expect(pill.closest(".cds--tag")).not.toBeNull();
            // And the role still reads as a role, because it resolved fine.
            const row = pill.closest("tr") as HTMLElement;
            expect(within(row).getByText("Editor").closest(".cds--tag")).not.toBeNull();
        });

        it("is counted apart from the rows that cannot be granted", async () => {
            previewCssBulkGrants.mockResolvedValue({
                data: { rows: [GOOD_ROW, HELD_ROW, BAD_ROW] },
            });

            renderPage();
            await upload();

            expect(
                await screen.findByText(
                    /1 permission\(s\) will be granted\. 1 row\(s\) are already granted and will be skipped\. 1 row\(s\) cannot be/
                )
            ).toBeInTheDocument();
        });

        it("is not counted as something the button will grant", async () => {
            previewCssBulkGrants.mockResolvedValue({
                data: { rows: [GOOD_ROW, HELD_ROW] },
            });

            renderPage();
            await upload();

            expect(
                await screen.findByRole("button", { name: "Grant 1 permission(s)" })
            ).toBeInTheDocument();
        });
    });

    it("counts what will be granted and what will be skipped", async () => {
        renderPage();
        await upload();

        expect(
            await screen.findByText(/1 permission\(s\) will be granted/)
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

    describe("saying so when the grant lands", () => {
        it("raises a toast, not just a table that changed under you", async () => {
            // The rows going from "Ready" to "Granted" is the only thing on
            // screen that moved, and on a long file it is easy to miss.
            createCssBulkGrants.mockResolvedValue({
                data: [{ ...GOOD_ROW, valid: true }],
            });

            renderPage();
            await upload();
            await userEvent.click(
                await screen.findByRole("button", { name: /^Grant / })
            );

            const toast = await screen.findByText("Permissions granted");
            expect(toast).toBeInTheDocument();
        });

        it("warns rather than congratulates when some rows did not make it", async () => {
            // A green toast over a partial result reads as "all done".
            renderPage();
            await upload();
            await userEvent.click(
                await screen.findByRole("button", { name: /^Grant / })
            );

            expect(
                await screen.findByText("Some permissions were not granted")
            ).toBeInTheDocument();
            expect(
                screen.queryByText("Permissions granted")
            ).not.toBeInTheDocument();
        });

        it("says nothing was granted when nothing was", async () => {
            createCssBulkGrants.mockResolvedValue({
                data: [{ ...BAD_ROW }],
            });

            renderPage();
            await upload();
            await userEvent.click(
                await screen.findByRole("button", { name: /^Grant / })
            );

            expect(
                await screen.findByText("Nothing was granted")
            ).toBeInTheDocument();
        });
    });

    it("turns the table into outcomes once granting has run", async () => {
        renderPage();
        await upload();
        await userEvent.click(
            await screen.findByRole("button", { name: "Grant 1 permission(s)" })
        );

        // "Ready" was a promise; "Granted" is a report, and the heading says so.
        expect(await screen.findByText("Granted")).toBeInTheDocument();
        // On the card - the toast says the same thing, and says it briefly.
        const card = document.querySelectorAll(".bulk-grant-card")[1] as HTMLElement;
        expect(
            within(card).getByText(/1 of 2 row\(s\) granted/)
        ).toBeInTheDocument();
        expect(
            screen.getByRole("button", { name: "Done" })
        ).toBeInTheDocument();
    });

    it("reports a whole-file refusal", async () => {
        // Empty, or too many rows - reported here rather than per row.
        previewCssBulkGrants.mockRejectedValue({
            response: { data: { detail: { description: "The file has no rows." } } },
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
            screen.getByRole("button", { name: "Remove file" })
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
