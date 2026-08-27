import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import {
    MemoryRouter,
    Route,
    Routes,
    useSearchParams,
} from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";

/**
 * The users table: what it shows, what it lets somebody do, and what it says
 * afterwards.
 */

const getCssUserRoleAssignments = vi.fn();
const deleteCssUserRoleAssignment = vi.fn();

vi.mock("@/services/ApiServiceFactory", () => ({
    AdminMgmtApiService: {
        cssIntegrationsApi: {
            getCssUserRoleAssignments: () => getCssUserRoleAssignments(),
            deleteCssUserRoleAssignment: (
                integrationId: number,
                environment: string,
                body: unknown
            ) => deleteCssUserRoleAssignment(integrationId, environment, body),
        },
    },
    AppActlApiService: {},
}));

const { CssPermissionsTable } = await import("./CssPermissionsTable");
const { NotificationProvider } = await import(
    "@/context/notification/NotificationProvider"
);

const ROW = {
    username: "JSMITH",
    user_guid: "AAAA1111",
    domain: "IDIR",
    first_name: "Jane",
    last_name: "Smith",
    email: "jane.smith@gov.bc.ca",
    role_name: "FREP_EDITOR_DISTRICT-DCC",
    role_display_name: "Editor",
    scopes: [{ type: "DISTRICT", value: "DCC", label: "Cariboo-Chilcotin" }],
};

const OTHER_ROW = {
    username: "BJONES",
    user_guid: "BBBB2222",
    domain: "BCEID",
    first_name: "Bob",
    last_name: "Jones",
    email: "bob@timber.example",
    role_name: "FREP_VIEWER",
    role_display_name: "Viewer",
    scopes: [],
};

/** Stands in for the edit screen and shows what it was handed. */
const EditLanding = () => {
    const [params] = useSearchParams();
    return <p>{`edit ${params.get("userGuid")} ${params.get("roleName")}`}</p>;
};

const renderTable = (props?: { newlyGrantedKeys?: string[] }) => {
    const queryClient = new QueryClient({
        defaultOptions: { queries: { retry: false } },
    });
    return render(
        <QueryClientProvider client={queryClient}>
            <MemoryRouter initialEntries={["/manage-permissions"]}>
                <NotificationProvider>
                    <Routes>
                        <Route
                            path="/manage-permissions"
                            element={
                                <CssPermissionsTable
                                    integrationId={6538}
                                    environment="dev"
                                    appName="FREP (DEV)"
                                    newlyGrantedKeys={props?.newlyGrantedKeys}
                                />
                            }
                        />
                        {/* Echoes the query back, so the key it carried is
                            assertable rather than mocked. */}
                        <Route
                            path="/manage-permissions/edit-app-permission"
                            element={<EditLanding />}
                        />
                    </Routes>
                </NotificationProvider>
            </MemoryRouter>
        </QueryClientProvider>
    );
};

/**
 * Carbon prefixes a danger button's accessible name with a visually-hidden
 * "danger", so the confirm button announces as "danger Remove". Matching on the
 * end of the name keeps the assertion honest without hard-coding that prefix.
 */
const CONFIRM_REMOVE = /Remove$/;

const rowFor = async (username: string) => {
    const cell = await screen.findByText(username);
    return cell.closest("tr") as HTMLElement;
};

describe("CssPermissionsTable", () => {
    beforeEach(() => {
        getCssUserRoleAssignments
            .mockReset()
            .mockResolvedValue({ data: [ROW, OTHER_ROW] });
        deleteCssUserRoleAssignment.mockReset().mockResolvedValue({ data: {} });
    });

    it("gives every column a cell, so nothing slides under the wrong heading", async () => {
        /*
            The count is the whole assertion, and it is here because a column was
            once added to the header list and not to the row: every cell after it
            shifted left, so the action buttons sat under "Expires" and the last
            heading stood over nothing. Each individual cell still rendered its
            own correct value, which is why nothing else noticed.
        */
        renderTable();

        // Waited for by content, not by role: the loading skeleton has headings
        // and cells of its own, and they are consistent with each other - so
        // measuring too early passes no matter how misaligned the real table is.
        await screen.findByText("JSMITH");

        const headings = screen.getAllByRole("columnheader");
        const firstRow = screen.getAllByRole("row")[1];

        expect(within(firstRow).getAllByRole("cell")).toHaveLength(headings.length);
    });

    it("leaves every cell a table cell, so none drops out of the row", async () => {
        /*
            A <td> given `display: flex` stops being a table cell: it leaves the
            row's layout, so it no longer takes the row's height and renders as
            a box of its own with the row showing through beneath it. It happened
            by putting a helper class meant for a <div> inside the cell onto the
            cell itself, and every value in it still rendered correctly - which
            is why nothing but the eye caught it.
        */
        renderTable();
        await screen.findByText("JSMITH");

        /*
            The class, not the computed display. jsdom loads no stylesheet, so
            every <td> computes as `table-cell` here however it is classed - an
            assertion on the display would pass for the broken markup too, which
            is exactly the trap this test exists to avoid falling into twice.
        */
        const cells = within(screen.getAllByRole("row")[1]).getAllByRole("cell");

        expect(
            cells.filter((cell) => cell.classList.contains("nowrap-cell"))
        ).toHaveLength(0);
    });

    it("shows a region by its name rather than its code", async () => {
        /*
            The role name is the only record of what a grant covers, so what
            comes back out of it is a code. A district code is short and
            familiar; a region code is SCREAMING_SNAKE_CASE and reads as
            something that escaped from a database.
        */
        getCssUserRoleAssignments.mockResolvedValue({
            data: [
                {
                    ...ROW,
                    scopes: [
                        {
                            type: "REGION",
                            value: "KOOTENAY_BOUNDARY",
                            label: "Kootenay-Boundary",
                        },
                    ],
                },
            ],
        });
        renderTable();

        expect(await screen.findByText("Kootenay-Boundary")).toBeInTheDocument();
        expect(screen.queryByText("KOOTENAY_BOUNDARY")).not.toBeInTheDocument();
    });

    it("still finds the row when somebody searches the region code", async () => {
        // The code is what a grant was made against and what somebody may have
        // been given in a ticket, so it stays searchable even once the chip
        // reads as a name.
        getCssUserRoleAssignments.mockResolvedValue({
            data: [
                {
                    ...ROW,
                    scopes: [
                        {
                            type: "REGION",
                            value: "KOOTENAY_BOUNDARY",
                            label: "Kootenay-Boundary",
                        },
                    ],
                },
            ],
        });
        renderTable();
        await screen.findByText("Kootenay-Boundary");

        await userEvent.type(
            screen.getByPlaceholderText("Search by keyword"),
            "KOOTENAY_BOUNDARY"
        );

        expect(await screen.findByText("Kootenay-Boundary")).toBeInTheDocument();
    });

    it("says when a permission ends, and says so plainly when it does not", async () => {
        renderTable();

        // "Never expires" rather than a blank cell: an empty column reads as
        // something that failed to load. Both fixture rows are open-ended.
        expect(await screen.findAllByText("Never expires")).toHaveLength(2);
    });

    it("shows the role by its display name and the scope by its label", async () => {
        renderTable();

        const row = await rowFor("JSMITH");
        // The short name, not the code: "Editor", not FREP_EDITOR_DISTRICT-DCC.
        expect(within(row).getByText("Editor")).toBeInTheDocument();
        expect(within(row).getByText("Cariboo-Chilcotin")).toBeInTheDocument();
    });

    it("marks only the rows a grant just created", async () => {
        // Keyed by user and role together - somebody's other, older permissions
        // must not light up alongside the one just added.
        // Both forms, as newlyGrantedKeys emits: the table shows whichever CSS
        // knew, and somebody who has never signed in appears as their GUID.
        renderTable({
            newlyGrantedKeys: [
                "AAAA1111|FREP_EDITOR_DISTRICT-DCC",
                "JSMITH|FREP_EDITOR_DISTRICT-DCC",
            ],
        });

        const granted = await rowFor("JSMITH");
        const untouched = await rowFor("BJONES");
        expect(within(granted).getByText("New")).toBeInTheDocument();
        expect(within(untouched).queryByText("New")).not.toBeInTheDocument();
    });

    it("filters on any column, including the scope", async () => {
        renderTable();
        await rowFor("JSMITH");

        await userEvent.type(
            screen.getByRole("searchbox"),
            "cariboo"
        );

        await waitFor(() =>
            expect(screen.queryByText("BJONES")).not.toBeInTheDocument()
        );
        expect(screen.getByText("JSMITH")).toBeInTheDocument();
    });

    it("does not filter on a term too short to mean anything", async () => {
        renderTable();
        await rowFor("JSMITH");

        // Two characters: below the minimum, so the table is left alone rather
        // than emptied while somebody is still typing.
        await userEvent.type(screen.getByRole("searchbox"), "ca");

        expect(screen.getByText("BJONES")).toBeInTheDocument();
        expect(
            screen.getByText(/at least 3 characters/)
        ).toBeInTheDocument();
    });

    it("asks before revoking, and does nothing if the answer is no", async () => {
        renderTable();
        const row = await rowFor("JSMITH");

        await userEvent.click(
            within(row).getByRole("button", { name: /^Remove /})
        );
        expect(
            await screen.findByText(/Are you sure you want to remove/)
        ).toBeInTheDocument();

        await userEvent.click(screen.getByRole("button", { name: "Cancel" }));

        expect(deleteCssUserRoleAssignment).not.toHaveBeenCalled();
    });

    it("names the role, the scope and the person in the confirmation", async () => {
        renderTable();
        const row = await rowFor("JSMITH");

        await userEvent.click(
            within(row).getByRole("button", { name: /^Remove /})
        );

        // An administrator revoking one of several districts needs to see which
        // one they are about to remove.
        const message = await screen.findByText(/Are you sure you want to remove/);
        expect(message.textContent).toContain("Editor");
        expect(message.textContent).toContain("Cariboo-Chilcotin");
        expect(message.textContent).toContain("JSMITH");
        expect(message.textContent).toContain("FREP (DEV)");
    });

    it("revokes by GUID and role, sending every scope", async () => {
        renderTable();
        const row = await rowFor("JSMITH");

        await userEvent.click(
            within(row).getByRole("button", { name: /^Remove /})
        );
        await userEvent.click(
            await screen.findByRole("button", { name: CONFIRM_REMOVE })
        );

        await waitFor(() =>
            expect(deleteCssUserRoleAssignment).toHaveBeenCalledTimes(1)
        );
        const [integrationId, environment, body] =
            deleteCssUserRoleAssignment.mock.calls[0];
        expect(integrationId).toBe(6538);
        expect(environment).toBe("dev");
        // The GUID, not the displayed username: that is a user id once the
        // directory has named them, and <guid>@azureidir before.
        expect(body).toMatchObject({
            user_guid: "AAAA1111",
            user_type: "IDIR",
            role_name: "FREP_EDITOR_DISTRICT-DCC",
            scopes: [{ type: "DISTRICT", values: ["DCC"] }],
        });
    });

    it("sends the BCeID user type for a BCeID row", async () => {
        // The same GUID may exist in both directories, so a removal that named
        // the wrong one would either miss or hit somebody else.
        renderTable();
        const row = await rowFor("BJONES");

        await userEvent.click(
            within(row).getByRole("button", { name: /^Remove /})
        );
        await userEvent.click(
            await screen.findByRole("button", { name: CONFIRM_REMOVE })
        );

        await waitFor(() =>
            expect(deleteCssUserRoleAssignment).toHaveBeenCalled()
        );
        expect(deleteCssUserRoleAssignment.mock.calls[0][2]).toMatchObject({
            user_type: "BCEID_BUS",
        });
    });

    it("says so out loud when a permission is removed", async () => {
        // The only other evidence is a row vanishing, which on a paginated table
        // can happen off-screen.
        renderTable();
        const row = await rowFor("JSMITH");

        await userEvent.click(
            within(row).getByRole("button", { name: /^Remove /})
        );
        await userEvent.click(
            await screen.findByRole("button", { name: CONFIRM_REMOVE })
        );

        const toast = await screen.findByRole("status");
        expect(within(toast).getByText("Permission removed")).toBeInTheDocument();
        expect(toast.textContent).toContain("Editor");
        expect(toast.textContent).toContain("JSMITH");
    });

    it("reports the backend's own reason when a revoke is refused", async () => {
        deleteCssUserRoleAssignment.mockRejectedValue({
            response: { data: { description: "You cannot revoke your own access." } },
        });
        renderTable();
        const row = await rowFor("JSMITH");

        await userEvent.click(
            within(row).getByRole("button", { name: /^Remove /})
        );
        await userEvent.click(
            await screen.findByRole("button", { name: CONFIRM_REMOVE })
        );

        // The reason - a self-revoke, another organisation - is worth more than
        // a status code, so it is shown rather than replaced with "try again".
        expect(
            await screen.findByText("You cannot revoke your own access.")
        ).toBeInTheDocument();
    });

    it("reports a failure to load rather than showing an empty table", async () => {
        getCssUserRoleAssignments.mockRejectedValue(new Error("CSS is down"));
        renderTable();

        expect(
            await screen.findByText("Failed to load permissions from CSS")
        ).toBeInTheDocument();
        // An empty table would read as "this application has no users".
        expect(screen.queryByText("No user found.")).not.toBeInTheDocument();
    });

    describe("grouping a role's scopes onto one row", () => {
        const regionRow = (value: string, label: string) => ({
            ...ROW,
            role_name: "FREP_VIEWER",
            role_display_name: "Viewer",
            scopes: [{ type: "REGION", value, label }],
        });

        it("shows one row for a role granted several times over", async () => {
            /*
                CSS has no idea of a scoped role, so a Viewer granted for three
                regions is three assignments. Three rows differing in one column
                made "who has what" something you had to collate by eye.
            */
            getCssUserRoleAssignments.mockResolvedValue({
                data: [
                    regionRow("SKEENA", "Skeena"),
                    regionRow("NORTHEAST", "Northeast"),
                    regionRow("KOOTENAY_BOUNDARY", "Kootenay-Boundary"),
                ],
            });
            renderTable();
            await screen.findByText("Skeena");

            // One body row, and every region on it.
            expect(screen.getAllByRole("row")).toHaveLength(2);
            expect(screen.getByText("Northeast")).toBeInTheDocument();
            expect(screen.getByText("Kootenay-Boundary")).toBeInTheDocument();
        });

        it("keeps a role's scopes plain when they are all of one kind", async () => {
            // Nothing to explain: each is an independent grant of the same
            // role, and a list is exactly what that is.
            getCssUserRoleAssignments.mockResolvedValue({
                data: [regionRow("SKEENA", "Skeena"), regionRow("NORTHEAST", "Northeast")],
            });
            renderTable();

            expect(await screen.findByText("Skeena")).toBeInTheDocument();
            expect(screen.queryByText("REG: Skeena")).not.toBeInTheDocument();
        });

        it("keeps a compound grant's scopes visibly paired", async () => {
            /*
                A submitter for a district AND an organisation holds the role for
                that pair. Flattened, four codes would read as four grants when
                they are two.
            */
            getCssUserRoleAssignments.mockResolvedValue({
                data: [
                    {
                        ...ROW,
                        role_name: "FREP_SUBMITTER",
                        role_display_name: "Submitter",
                        scopes: [
                            { type: "DISTRICT", value: "DCC" },
                            { type: "FOREST_CLIENT", value: "00001012" },
                        ],
                    },
                    {
                        ...ROW,
                        role_name: "FREP_SUBMITTER",
                        role_display_name: "Submitter",
                        scopes: [
                            { type: "DISTRICT", value: "DKA" },
                            { type: "FOREST_CLIENT", value: "00001012" },
                        ],
                    },
                ],
            });
            renderTable();

            // Prefixed, because the column now carries two kinds of value.
            expect(await screen.findByText("DIS: DCC")).toBeInTheDocument();
            expect(screen.getByText("DIS: DKA")).toBeInTheDocument();
            expect(screen.getAllByText("ORG: 00001012")).toHaveLength(2);
            // One line per pairing, not one flat list.
            expect(
                document.querySelectorAll(".scope-combinations .scope-chips")
            ).toHaveLength(2);
        });

        it("removes every assignment behind the row, not just the first", async () => {
            // The row is a role. Removing it and leaving two of three regions
            // behind would report success for something that did not happen.
            getCssUserRoleAssignments.mockResolvedValue({
                data: [
                    regionRow("SKEENA", "Skeena"),
                    regionRow("NORTHEAST", "Northeast"),
                ],
            });
            renderTable();
            await screen.findByText("Skeena");

            await userEvent.click(
                screen.getByRole("button", { name: /^Remove /})
            );
            await userEvent.click(
                screen.getByRole("button", { name: CONFIRM_REMOVE })
            );

            await waitFor(() =>
                expect(deleteCssUserRoleAssignment).toHaveBeenCalledTimes(2)
            );
            const sent = deleteCssUserRoleAssignment.mock.calls.map(
                (call) => call[2].scopes[0].values[0]
            );
            expect(sent).toEqual(["SKEENA", "NORTHEAST"]);
        });

        it("names every scope it is about to remove", async () => {
            // One click now takes a whole role. A confirmation naming only the
            // first would understate it, and a count would not tell somebody
            // whether the one they care about is among them.
            getCssUserRoleAssignments.mockResolvedValue({
                data: [
                    regionRow("SKEENA", "Skeena"),
                    regionRow("NORTHEAST", "Northeast"),
                ],
            });
            renderTable();
            await screen.findByText("Skeena");

            await userEvent.click(
                screen.getByRole("button", { name: /^Remove /})
            );

            const dialog = await screen.findByRole("dialog");
            expect(within(dialog).getByText(/Skeena, Northeast/)).toBeInTheDocument();
        });

        it("keeps grants that end on different days apart", async () => {
            // One date cannot describe both, and it would be the wrong one for
            // half the scopes.
            getCssUserRoleAssignments.mockResolvedValue({
                data: [
                    { ...regionRow("SKEENA", "Skeena"), expires_on: "2026-09-30" },
                    { ...regionRow("NORTHEAST", "Northeast"), expires_on: "2026-12-31" },
                ],
            });
            renderTable();
            await screen.findByText("Skeena");

            expect(screen.getAllByRole("row")).toHaveLength(3);
        });
    });

    it("puts the role before the scope it narrows", async () => {
        // A scope means nothing until you know which role it applies to - and a
        // grouped row is one role with several scopes beneath it.
        renderTable();
        await screen.findByText("JSMITH");

        /*
            The heading and the cell under it, together.

            Checking the heading order alone is what let this ship backwards
            once already: the header array was reordered and the cells were not,
            so the table read "Role | Scope" over a scope and a role. Carbon
            wraps a sortable heading's text in its own button, hence `includes`
            rather than an exact match.
        */
        const headings = screen
            .getAllByRole("columnheader")
            .map((cell) => cell.textContent ?? "");
        const at = (name: string) =>
            headings.findIndex((heading) => heading.includes(name));

        expect(at("Role")).toBeGreaterThanOrEqual(0);
        expect(at("Role")).toBeLessThan(at("Scope"));

        const cells = within(await rowFor("JSMITH")).getAllByRole("cell");
        expect(cells[at("Role")].textContent).toContain("Editor");
        expect(cells[at("Scope")].textContent).toContain("Cariboo-Chilcotin");
    });

    it("offers an edit beside the remove, carrying the row's own key", async () => {
        /*
            The key, not a row index: the table is paginated, sorted and
            grouped, so a position means nothing on the far side of a page load.
            These three identify the same grant on arrival.
        */
        renderTable();
        const row = await rowFor("JSMITH");

        const edit = within(row).getByRole("button", { name: /^Edit / });
        await userEvent.click(edit);

        // The row's own guid and role name, whatever they are - that pair plus
        // the expiry is exactly what grouped the row in the first place.
        expect(
            await screen.findByText(`edit ${ROW.user_guid} ${ROW.role_name}`)
        ).toBeInTheDocument();
    });

    it("puts the edit to the left of the remove", async () => {
        // Destructive last: the two sit together, and the one that cannot be
        // undone should not be the one nearest the thumb.
        renderTable();
        const row = await rowFor("JSMITH");

        const names = within(row)
            .getAllByRole("button")
            .map((button) => button.getAttribute("aria-label") ?? "");
        const editAt = names.findIndex((name) => name.startsWith("Edit "));
        const removeAt = names.findIndex((name) => name.startsWith("Remove "));

        expect(editAt).toBeGreaterThanOrEqual(0);
        expect(editAt).toBeLessThan(removeAt);
    });
});

