import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
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

const renderTable = (props?: { newlyGrantedKeys?: string[] }) => {
    const queryClient = new QueryClient({
        defaultOptions: { queries: { retry: false } },
    });
    return render(
        <QueryClientProvider client={queryClient}>
            <MemoryRouter>
                <NotificationProvider>
                    <CssPermissionsTable
                        integrationId={6538}
                        environment="dev"
                        appName="FREP (DEV)"
                        newlyGrantedKeys={props?.newlyGrantedKeys}
                    />
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
            within(row).getByRole("button", { name: "Delete user permission" })
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
            within(row).getByRole("button", { name: "Delete user permission" })
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
            within(row).getByRole("button", { name: "Delete user permission" })
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
            within(row).getByRole("button", { name: "Delete user permission" })
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
            within(row).getByRole("button", { name: "Delete user permission" })
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
            within(row).getByRole("button", { name: "Delete user permission" })
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
});
