import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { NotificationProvider } from "@/context/notification/NotificationProvider";

/**
 * Changing what one person's role is granted for.
 *
 * <p>The grant screen with its first step removed - who this is for was settled
 * by the row that was clicked. What is worth pinning is the two halves that
 * cannot be seen by looking at the page: that it opens on what the person
 * already holds, and that saving sends only what changed.
 */

const getCssUserRoleAssignments = vi.fn();
const getCssApplicationRoles = vi.fn();
const createCssUserRoleAssignment = vi.fn();
const deleteCssUserRoleAssignment = vi.fn();
const getRegions = vi.fn();

vi.mock("@/services/ApiServiceFactory", () => ({
    AdminMgmtApiService: {
        cssIntegrationsApi: {
            getCssUserRoleAssignments: () => getCssUserRoleAssignments(),
            getCssApplicationRoles: () => getCssApplicationRoles(),
            createCssUserRoleAssignment: (
                integrationId: number,
                environment: string,
                body: unknown
            ) => createCssUserRoleAssignment(integrationId, environment, body),
            deleteCssUserRoleAssignment: (
                integrationId: number,
                environment: string,
                body: unknown
            ) => deleteCssUserRoleAssignment(integrationId, environment, body),
            getCssApplications: () => Promise.resolve({ data: [] }),
        },
    },
    AppActlApiService: {
        districtsApi: { getDistricts: () => Promise.resolve({ data: [] }) },
        regionsApi: { getRegions: () => getRegions() },
        forestClientsApi: {},
    },
}));

const { EditAppPermission } = await import("./index");
const { SelectedAppProvider } = await import(
    "@/context/application/SelectedAppProvider"
);

const VIEWER = {
    name: "FREP_VIEWER",
    display_name: "Viewer",
    role_type_district: false,
    role_type_region: true,
    role_type_client: false,
};

const assignment = (region: string) => ({
    username: "JSMITH",
    user_guid: "B2",
    domain: "IDIR",
    first_name: "Jane",
    last_name: "Smith",
    email: "jane@gov.bc.ca",
    role_name: "FREP_VIEWER",
    role_display_name: "Viewer",
    scopes: [{ type: "REGION", value: region, label: region }],
});

const QUERY =
    "?integrationId=6538&environment=dev&userGuid=B2&roleName=FREP_VIEWER&expiresOn=";

const renderPage = (query = QUERY) =>
    render(
        <QueryClientProvider
            client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}
        >
            <MemoryRouter initialEntries={[`/manage-permissions/edit-app-permission${query}`]}>
                <NotificationProvider>
                <SelectedAppProvider>
                    <Routes>
                        <Route
                            path="/manage-permissions/edit-app-permission"
                            element={<EditAppPermission />}
                        />
                        <Route
                            path="/manage-permissions"
                            element={<p>Manage permissions landed</p>}
                        />
                    </Routes>
                </SelectedAppProvider>
                </NotificationProvider>
            </MemoryRouter>
        </QueryClientProvider>
    );

const scopePanel = () =>
    document.querySelector(".role-scope-fields") as HTMLElement;

describe("EditAppPermission", () => {
    beforeEach(() => {
        getCssUserRoleAssignments
            .mockReset()
            .mockResolvedValue({
                data: [assignment("SKEENA"), assignment("NORTHEAST")],
            });
        getCssApplicationRoles.mockReset().mockResolvedValue({ data: [VIEWER] });
        getRegions.mockReset().mockResolvedValue({
            data: [
                { region_code: "SKEENA", region_name: "Skeena", expired: false },
                { region_code: "NORTHEAST", region_name: "Northeast", expired: false },
                { region_code: "CARIBOO", region_name: "Cariboo", expired: false },
            ],
        });
        createCssUserRoleAssignment.mockReset().mockResolvedValue({ data: [] });
        deleteCssUserRoleAssignment.mockReset().mockResolvedValue({ data: {} });
    });

    it("says who is being edited instead of asking", async () => {
        // The row that was clicked already answered it, and a search box would
        // invite changing the answer - which is a different operation.
        renderPage();

        // Named in the step where the grant screen would put a search box.
        const step = (await screen.findByText("Editing:")).closest(
            ".unscoped-summary"
        ) as HTMLElement;
        expect(within(step).getByText(/JSMITH/)).toBeInTheDocument();

        expect(
            screen.queryByRole("button", { name: /Search users/ })
        ).not.toBeInTheDocument();
        expect(screen.queryByText(/will receive the same/)).not.toBeInTheDocument();
    });

    it("opens with what the person already holds", async () => {
        // A blank form would read as "grant this again", and saving it would
        // take away everything it failed to show.
        renderPage();

        await waitFor(() => expect(scopePanel()).not.toBeNull());
        expect(within(scopePanel()).getByText("Skeena")).toBeInTheDocument();
        expect(within(scopePanel()).getByText("Northeast")).toBeInTheDocument();
    });

    it("names the regions rather than showing their codes", async () => {
        renderPage();

        await waitFor(() => expect(scopePanel()).not.toBeNull());
        expect(within(scopePanel()).queryByText("KOOTENAY_BOUNDARY")).toBeNull();
        expect(within(scopePanel()).getByText("Skeena")).toBeInTheDocument();
    });

    it("sends nothing at all when nothing was changed", async () => {
        renderPage();
        await waitFor(() => expect(scopePanel()).not.toBeNull());

        await userEvent.click(screen.getByRole("button", { name: "Save changes" }));

        await waitFor(() =>
            expect(screen.getByText("Manage permissions landed")).toBeInTheDocument()
        );
        expect(createCssUserRoleAssignment).not.toHaveBeenCalled();
        expect(deleteCssUserRoleAssignment).not.toHaveBeenCalled();
    });

    it("grants only the scope that was added", async () => {
        renderPage();
        await waitFor(() => expect(scopePanel()).not.toBeNull());

        await userEvent.click(screen.getByRole("combobox", { name: "Region" }));
        await userEvent.click(await screen.findByText("Cariboo"));
        await userEvent.click(screen.getByRole("button", { name: "Save changes" }));

        await waitFor(() =>
            expect(createCssUserRoleAssignment).toHaveBeenCalledTimes(1)
        );
        expect(createCssUserRoleAssignment.mock.calls[0][2].scopes).toEqual([
            { type: "REGION", values: ["CARIBOO"] },
        ]);
        // The two it already had are untouched.
        expect(deleteCssUserRoleAssignment).not.toHaveBeenCalled();
    });

    it("revokes only the scope that was taken away", async () => {
        renderPage();
        await waitFor(() => expect(scopePanel()).not.toBeNull());

        await userEvent.click(
            within(scopePanel()).getByRole("button", { name: /Remove Skeena/ })
        );
        await userEvent.click(screen.getByRole("button", { name: "Save changes" }));

        await waitFor(() =>
            expect(deleteCssUserRoleAssignment).toHaveBeenCalledTimes(1)
        );
        expect(
            deleteCssUserRoleAssignment.mock.calls[0][2].scopes[0].values
        ).toEqual(["SKEENA"]);
        expect(createCssUserRoleAssignment).not.toHaveBeenCalled();
    });

    it("adds before it removes", async () => {
        /*
            If the two halves cannot both succeed, the person is better left
            holding too much - which the table shows and somebody can act on -
            than too little, which locks them out with nothing on screen to say
            why.
        */
        const order: string[] = [];
        createCssUserRoleAssignment.mockImplementation(() => {
            order.push("grant");
            return Promise.resolve({ data: [] });
        });
        deleteCssUserRoleAssignment.mockImplementation(() => {
            order.push("revoke");
            return Promise.resolve({ data: {} });
        });

        renderPage();
        await waitFor(() => expect(scopePanel()).not.toBeNull());

        await userEvent.click(screen.getByRole("combobox", { name: "Region" }));
        await userEvent.click(await screen.findByText("Cariboo"));
        await userEvent.click(
            within(scopePanel()).getByRole("button", { name: /Remove Skeena/ })
        );
        await userEvent.click(screen.getByRole("button", { name: "Save changes" }));

        await waitFor(() => expect(order).toHaveLength(2));
        expect(order).toEqual(["grant", "revoke"]);
    });

    it("says so rather than showing an empty form when the grant has gone", async () => {
        // Removed since the table was loaded. A blank edit form would invite
        // re-creating it by accident.
        getCssUserRoleAssignments.mockResolvedValue({ data: [] });
        renderPage();

        expect(
            await screen.findByText(/could not be found/)
        ).toBeInTheDocument();
    });

    /*
        Not tested here: changing the expiry and saving.

        The picker is flatpickr-backed, and once it carries a value it keeps a
        calendar mounted over the form that neither userEvent nor fireEvent will
        submit through in jsdom. Driving it any harder would be testing the
        harness rather than the page.

        The decision it feeds is covered on its own - see plannedGrants and
        isNoop in editUtils.spec.ts - so what is untested is the wiring between
        the field and that decision, not the behaviour itself.
    */
});

