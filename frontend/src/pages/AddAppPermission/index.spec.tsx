import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { AuthContext, type AuthContextValue } from "@/context/auth/AuthContext";

/**
 * Granting roles to people.
 *
 * The behaviours worth holding onto are the ones that took work to get right:
 * the form revealing itself a step at a time, one call per user per role, and a
 * refusal on one pair not discarding the grants that already landed.
 */

const searchIdirUsers = vi.fn();
const getCssApplications = vi.fn();
const getCssApplicationRoles = vi.fn();
const createCssUserRoleAssignment = vi.fn();
const getDistricts = vi.fn();
const getRegions = vi.fn();
const navigate = vi.fn();

vi.mock("@/services/ApiServiceFactory", () => ({
    AppActlApiService: {
        idirBceidProxyApi: {
            searchIdirUsers: () => searchIdirUsers(),
            bceidLookup: () => Promise.resolve({ data: { found: false } }),
        },
        districtsApi: { getDistricts: () => getDistricts() },
        regionsApi: { getRegions: () => getRegions() },
        forestClientsApi: {
            autocompleteForestClients: () => Promise.resolve({ data: [] }),
        },
    },
    AdminMgmtApiService: {
        cssIntegrationsApi: {
            getCssApplications: () => getCssApplications(),
            getCssApplicationRoles: () => getCssApplicationRoles(),
            createCssUserRoleAssignment: (
                integrationId: number,
                environment: string,
                request: unknown
            ) => createCssUserRoleAssignment(integrationId, environment, request),
        },
    },
}));

vi.mock("react-router-dom", async () => {
    const actual = await vi.importActual<typeof import("react-router-dom")>(
        "react-router-dom"
    );
    return { ...actual, useNavigate: () => navigate };
});

const { AddAppPermission } = await import("./index");
const { SelectedAppProvider } = await import(
    "@/context/application/SelectedAppProvider"
);
const { NotificationProvider } = await import(
    "@/context/notification/NotificationProvider"
);

const VIEWER = {
    name: "FREP_VIEWER",
    display_name: "Viewer",
    description: "Read only",
    role_type_district: false,
    role_type_client: false,
};

const EDITOR = {
    name: "FREP_EDITOR",
    display_name: "Editor",
    description: "Edit within a district",
    role_type_district: true,
    role_type_client: false,
};

const REGIONAL = {
    name: "FREP_REGIONAL",
    display_name: "Regional lead",
    description: "Lead within a region",
    role_type_district: false,
    role_type_region: true,
    role_type_client: false,
};

const IDIR_RESULT = {
    items: [
        {
            userId: "JSMITH",
            guid: "AAAA1111",
            firstName: "Jane",
            lastName: "Smith",
            email: "jane.smith@gov.bc.ca",
        },
    ],
};

const renderPage = () => {
    const auth: AuthContextValue = {
        authState: {
            isAuthenticated: true,
            famLoginUser: { username: "ADMINUSER" },
            isAuthRestored: true,
            accessRoles: ["FAM_ADMIN"],
        },
        login: async () => {},
        logout: async () => {},
        ensureFreshToken: async () => {},
        forceRefreshSession: async () => {},
    };
    const queryClient = new QueryClient({
        defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    });
    return render(
        <QueryClientProvider client={queryClient}>
            <AuthContext.Provider value={auth}>
                <MemoryRouter
                    initialEntries={[
                        "/manage-permissions/add-app-permission?integrationId=6538&environment=dev",
                    ]}
                >
                    <NotificationProvider>
                        <SelectedAppProvider>
                            <Routes>
                                <Route
                                    path="/manage-permissions/add-app-permission"
                                    element={<AddAppPermission />}
                                />
                            </Routes>
                        </SelectedAppProvider>
                    </NotificationProvider>
                </MemoryRouter>
            </AuthContext.Provider>
        </QueryClientProvider>
    );
};

/**
 * Runs the directory search and adds the one person it returns.
 *
 * No tick needed: a single result is not a choice, so the modal arrives with it
 * already selected. Clicking it here would turn it off and disable Confirm.
 */
const chooseUser = async () => {
    await userEvent.type(screen.getByRole("textbox"), "smith");
    await userEvent.click(screen.getByRole("button", { name: "Search users" }));
    const dialog = await screen.findByRole("dialog");
    await userEvent.click(within(dialog).getByRole("button", { name: "Confirm" }));
    await waitFor(() =>
        expect(
            screen.getByRole("heading", { name: "Select the roles to grant" })
        ).toBeInTheDocument()
    );
};

const tickRole = async (label: string) => {
    const table = document.querySelector(
        ".role-multi-select-table"
    ) as HTMLElement;
    await userEvent.click(within(table).getByLabelText(label));
};

/**
 * Picks a district or region from its combobox.
 *
 * They used to be checkbox lists, so a test ticked a label. They are a select
 * box plus a table of what was chosen now, matching the organisation picker.
 */
const pickScope = async (noun: "District" | "Region", name: string) => {
    await userEvent.click(screen.getByRole("combobox", { name: noun }));
    await userEvent.click(await screen.findByText(name));
};

/**
 * One role's row in the picker.
 *
 * Found through the row's checkbox, whose accessible name is the role's - the
 * label text alone appears in several places on the page.
 */
const roleRow = (label: string) => {
    const table = document.querySelector(
        ".role-multi-select-table"
    ) as HTMLElement;
    return within(table)
        .getByLabelText(label, { exact: true })
        .closest("tr") as HTMLElement;
};

/** The open scope panel inside a role's own row. */
const scopePanel = () =>
    document.querySelector(".role-scope-fields") as HTMLElement;


describe("AddAppPermission", () => {
    beforeEach(() => {
        searchIdirUsers.mockReset().mockResolvedValue({ data: IDIR_RESULT });
        getCssApplications.mockReset().mockResolvedValue({
            data: [
                {
                    integration_id: 6538,
                    environment: "dev",
                    name: "FREP",
                    description: "FREP (DEV)",
                    fam_application: false,
                },
            ],
        });
        getCssApplicationRoles
            .mockReset()
            .mockResolvedValue({ data: [VIEWER, EDITOR, REGIONAL] });
        createCssUserRoleAssignment
            .mockReset()
            .mockResolvedValue({ data: [{ assigned: true }] });
        getRegions.mockReset().mockResolvedValue({
            data: [
                { region_code: "CARIBOO", region_name: "Cariboo", expired: false },
                { region_code: "SKEENA", region_name: "Skeena", expired: false },
            ],
        });
        getDistricts.mockReset().mockResolvedValue({
            data: [
                { org_unit_code: "DCC", org_unit_name: "Cariboo", expired: false },
                { org_unit_code: "DKA", org_unit_name: "Kamloops", expired: false },
            ],
        });
        navigate.mockReset();
    });

    it("names the application in the subtitle", async () => {
        // It said "Grant a role to DEV" - true, but it does not say which
        // application, and this renders with an empty selected-app context,
        // which is what a bookmark or a refresh leaves behind. That is exactly
        // when the subtitle used to degrade to the bare environment.
        renderPage();

        expect(
            await screen.findByText("Grant a role to FREP (DEV)")
        ).toBeInTheDocument();
    });

    it("names the application the URL points at, not whichever came back first", async () => {
        // The query string is what the form actually grants against, so a
        // different application must never end up in the heading - it would
        // describe a grant that is not the one about to happen.
        //
        // The decoy is deliberately first in the list, and the assertion is
        // positive: waiting for the target's name to appear is the only form
        // that cannot pass before the list has arrived.
        getCssApplications.mockResolvedValue({
            data: [
                {
                    integration_id: 999,
                    environment: "prod",
                    name: "OTHER",
                    description: "OTHER (PROD)",
                    fam_application: false,
                },
                {
                    integration_id: 6538,
                    environment: "dev",
                    name: "FREP",
                    description: "FREP (DEV)",
                    fam_application: false,
                },
            ],
        });
        renderPage();

        expect(
            await screen.findByText("Grant a role to FREP (DEV)")
        ).toBeInTheDocument();
        expect(screen.queryByText(/OTHER/)).not.toBeInTheDocument();
    });

    it("carries the application name into the grant summary", async () => {
        // The toast and the failure banner on Manage permissions read it from
        // here, so the same fallback bit them too.
        renderPage();
        await chooseUser();
        await tickRole("Viewer");
        await userEvent.click(
            screen.getByRole("button", { name: "Grant permission" })
        );

        const toast = await screen.findByRole("status");
        expect(toast.textContent).toContain("FREP (DEV)");
    });

    it("withholds the role step until somebody is chosen", async () => {
        // It put a role table and an empty scope picker in front of somebody who
        // had not yet said who this was for.
        renderPage();

        expect(
            screen.queryByRole("heading", { name: "Select the roles to grant" })
        ).not.toBeInTheDocument();

        await chooseUser();

        expect(
            screen.getByRole("heading", { name: "Select the roles to grant" })
        ).toBeInTheDocument();
    });

    it("opens the scope fields inside the role's own row when it is ticked", async () => {
        // The pickers used to appear in a step further down. Inline, they cannot
        // be mistaken for another role's - and a role granted outright still
        // opens nothing, because it has nothing to choose.
        renderPage();
        await chooseUser();

        await tickRole("Viewer");
        expect(scopePanel()).toBeNull();
        // And no chevron either: an expander that opens on an empty panel reads
        // as a fault, so an unscoped role does not get one.
        expect(
            within(roleRow("Viewer")).queryByRole("button", { name: /Scope for/ })
        ).not.toBeInTheDocument();

        await tickRole("Editor");

        await waitFor(() => expect(scopePanel()).not.toBeNull());
        expect(
            within(scopePanel()).getByRole("combobox", { name: "District" })
        ).toBeInTheDocument();
    });

    it("will not let a chosen role's scope fields be closed", async () => {
        // The panel is not a disclosure - it is the rest of the question the
        // checkbox asks. Closing it hid the very fields the form refuses to
        // submit without, and the submit button then did nothing with nothing on
        // screen saying why.
        renderPage();
        await chooseUser();
        await tickRole("Editor");
        await waitFor(() => expect(scopePanel()).not.toBeNull());

        await userEvent.click(
            within(roleRow("Editor")).getByRole("button", { name: /Scope for/ })
        );

        expect(scopePanel()).not.toBeNull();
    });

    it("closes it when the role itself is unticked", async () => {
        // Which leaves the checkbox as the only control over the panel.
        renderPage();
        await chooseUser();
        await tickRole("Editor");
        await waitFor(() => expect(scopePanel()).not.toBeNull());

        await tickRole("Editor");

        await waitFor(() => expect(scopePanel()).toBeNull());
    });

    it("closes the scope fields again when the role is unticked", async () => {
        renderPage();
        await chooseUser();
        await tickRole("Editor");
        await waitFor(() => expect(scopePanel()).not.toBeNull());

        await tickRole("Editor");

        await waitFor(() => expect(scopePanel()).toBeNull());
    });

    it("lists an unscoped role rather than giving it an empty card", async () => {
        renderPage();
        await chooseUser();
        await tickRole("Viewer");

        expect(
            screen.getByText("Granted for the whole application:")
        ).toBeInTheDocument();
    });

    it("counts what will be created, multiplying scope by user", async () => {
        // Not obvious from the form, and it is what runs into the backend's
        // ceiling: one user, one role, two districts is two permissions.
        renderPage();
        await chooseUser();
        await tickRole("Editor");

        await pickScope("District", "Cariboo");
        await pickScope("District", "Kamloops");

        await waitFor(() =>
            expect(screen.getByText(/This will create/).textContent).toContain("2")
        );
    });

    it("refuses to submit a scoped role with nothing chosen for it", async () => {
        renderPage();
        await chooseUser();
        await tickRole("Editor");

        await userEvent.click(
            screen.getByRole("button", { name: "Grant permission" })
        );

        expect(
            await screen.findByText("Choose at least one district for this role")
        ).toBeInTheDocument();
        expect(createCssUserRoleAssignment).not.toHaveBeenCalled();
        // Beside the field and nowhere else: a second copy at the foot of the
        // form said nothing the first did not.
        expect(
            screen.queryByText(/Check the highlighted fields/)
        ).not.toBeInTheDocument();
    });

    it("sends one request per user per role", async () => {
        // CSS assigns a single role to a single user at a time.
        renderPage();
        await chooseUser();
        await tickRole("Viewer");
        await tickRole("Editor");

        await pickScope("District", "Cariboo");

        await userEvent.click(
            screen.getByRole("button", { name: "Grant permission" })
        );

        await waitFor(() =>
            expect(createCssUserRoleAssignment).toHaveBeenCalledTimes(2)
        );
        const sent = createCssUserRoleAssignment.mock.calls.map(
            (call) => call[2]
        );
        expect(sent).toEqual([
            expect.objectContaining({
                user_guid: "AAAA1111",
                role_name: "FREP_VIEWER",
                // An unscoped role contributes no scope selections at all.
                scopes: [],
            }),
            expect.objectContaining({
                user_guid: "AAAA1111",
                role_name: "FREP_EDITOR",
                scopes: [{ type: "DISTRICT", values: ["DCC"] }],
            }),
        ]);
    });

    it("grants a region-scoped role for the region chosen", async () => {
        // Regions are their own dimension. The scope has to reach the request as
        // REGION, because the backend composes the role name from it - a value
        // sent under the wrong type names a role nobody holds.
        renderPage();
        await chooseUser();
        await tickRole("Regional lead");
        await waitFor(() => expect(scopePanel()).not.toBeNull());

        await pickScope("Region", "Cariboo");

        await userEvent.click(
            screen.getByRole("button", { name: "Grant permission" })
        );

        await waitFor(() =>
            expect(createCssUserRoleAssignment).toHaveBeenCalled()
        );
        expect(createCssUserRoleAssignment.mock.calls[0][2]).toMatchObject({
            role_name: "FREP_REGIONAL",
            scopes: [{ type: "REGION", values: ["CARIBOO"] }],
        });
    });

    it("refuses to submit a region-scoped role with no region chosen", async () => {
        renderPage();
        await chooseUser();
        await tickRole("Regional lead");

        await userEvent.click(
            screen.getByRole("button", { name: "Grant permission" })
        );

        expect(
            await screen.findByText("Choose at least one region for this role")
        ).toBeInTheDocument();
        expect(createCssUserRoleAssignment).not.toHaveBeenCalled();
    });

    it("offers a region picker only for a role that needs one", async () => {
        renderPage();
        await chooseUser();
        await tickRole("Editor");
        await waitFor(() => expect(scopePanel()).not.toBeNull());

        // Editor is district-scoped, so its row carries the district picker and
        // no region one - the two are separate dimensions, and offering a picker
        // the role does not use would read as a restriction that is not there.
        expect(
            within(scopePanel()).getByRole("combobox", { name: "District" })
        ).toBeInTheDocument();
        expect(
            within(scopePanel()).queryByRole("combobox", { name: "Region" })
        ).not.toBeInTheDocument();
    });

    it("carries the email so the notification can be addressed", async () => {
        renderPage();
        await chooseUser();
        await tickRole("Viewer");
        await userEvent.click(
            screen.getByRole("button", { name: "Grant permission" })
        );

        await waitFor(() =>
            expect(createCssUserRoleAssignment).toHaveBeenCalled()
        );
        expect(createCssUserRoleAssignment.mock.calls[0][2]).toMatchObject({
            target_user_email: "jane.smith@gov.bc.ca",
        });
    });

    it("spins the grant button while the grant is running", async () => {
        // A grant is one CSS call per user per role, run in sequence. Without
        // this the button just goes dead and the screen looks broken.
        let release: (value: unknown) => void = () => {};
        createCssUserRoleAssignment.mockReturnValue(
            new Promise((resolve) => {
                release = resolve;
            })
        );
        const { container } = renderPage();
        await chooseUser();
        await tickRole("Viewer");

        expect(container.querySelector(".cds--loading")).toBeNull();

        await userEvent.click(
            screen.getByRole("button", { name: "Grant permission" })
        );

        await waitFor(() =>
            expect(container.querySelector(".cds--loading")).not.toBeNull()
        );
        release({ data: [{ assigned: true }] });
    });

    it("keeps the grants that landed when another is refused", async () => {
        // They have happened in CSS and cannot be taken back by failing here.
        createCssUserRoleAssignment
            .mockResolvedValueOnce({ data: [{ assigned: true }] })
            .mockRejectedValueOnce({
                response: { data: { description: "Not yours to grant" } },
            });
        renderPage();
        await chooseUser();
        await tickRole("Viewer");
        await tickRole("Editor");

        await pickScope("District", "Cariboo");
        await userEvent.click(
            screen.getByRole("button", { name: "Grant permission" })
        );

        // Both attempted, and the screen still moves on - the successful half is
        // real.
        await waitFor(() =>
            expect(createCssUserRoleAssignment).toHaveBeenCalledTimes(2)
        );
        await waitFor(() =>
            expect(navigate).toHaveBeenCalledWith("/manage-permissions")
        );
    });

    it("announces a clean grant and returns to the table", async () => {
        renderPage();
        await chooseUser();
        await tickRole("Viewer");
        await userEvent.click(
            screen.getByRole("button", { name: "Grant permission" })
        );

        const toast = await screen.findByRole("status");
        expect(within(toast).getByText("Permission granted")).toBeInTheDocument();
        expect(toast.textContent).toContain("Viewer");
        expect(toast.textContent).toContain("Jane Smith (JSMITH)");
        await waitFor(() =>
            expect(navigate).toHaveBeenCalledWith("/manage-permissions")
        );
    });

    it("drops everything chosen for a role when the role is unticked", async () => {
        // A silently retained selection would be re-submitted by somebody who
        // thought they had cleared it.
        renderPage();
        await chooseUser();
        await tickRole("Editor");

        await pickScope("District", "Cariboo");
        await waitFor(() =>
            expect(screen.getByText(/This will create/)).toBeInTheDocument()
        );

        await tickRole("Editor");
        await tickRole("Editor");

        // The row is open again, and empty: zero permissions, not one.
        await waitFor(() => expect(scopePanel()).not.toBeNull());
        expect(screen.queryByText(/This will create/)).not.toBeInTheDocument();
    });

    /*
        Striping, which has to follow the role rather than the row.

        A role is one <tr> when it is unscoped and two when its panel is open,
        so anything counting rows loses its place at the first unscoped role -
        Carbon's zebra counts in fours and did exactly that, putting a role's
        panel on the opposite stripe to the role itself.
    */
    it("lists the roles in code order, not the order CSS returned them", async () => {
        // CSS answers in an order that is neither alphabetical nor stable, so
        // the same application listed its roles differently between two visits.
        getCssApplicationRoles.mockResolvedValue({
            data: [REGIONAL, VIEWER, EDITOR],
        });
        renderPage();
        await chooseUser();

        const table = document.querySelector(
            ".role-multi-select-table"
        ) as HTMLElement;
        await waitFor(() =>
            expect(within(table).getByLabelText("Editor")).toBeInTheDocument()
        );

        const names = [...table.querySelectorAll("tbody tr")]
            .map((row) => row.querySelector("td:nth-child(3)")?.textContent?.trim())
            .filter(Boolean);

        // FREP_EDITOR, FREP_REGIONAL, FREP_VIEWER.
        expect(names).toEqual(["Editor", "Regional lead", "Viewer"]);
    });

    describe("row striping", () => {
        const shaded = (row: HTMLElement) =>
            row.classList.contains("role-row--shaded");

        /** The panel row, which is the <tr> after the role's own. */
        const panelRowFor = (label: string) =>
            roleRow(label).nextElementSibling as HTMLElement;

        it("gives a role's panel the same stripe as its row", async () => {
            renderPage();
            await chooseUser();
            await tickRole("Editor");

            await waitFor(() => expect(scopePanel()).not.toBeNull());

            const row = roleRow("Editor");
            const panel = panelRowFor("Editor");
            expect(panel).toHaveAttribute("data-child-row");
            expect(shaded(panel)).toBe(shaded(row));
        });

        it("keeps counting roles, not rows, past an expanded one", async () => {
            renderPage();
            await chooseUser();
            await tickRole("Editor");
            await waitFor(() => expect(scopePanel()).not.toBeNull());

            // Sorted by code, the order is EDITOR, REGIONAL, VIEWER. The stripe
            // alternates per role, so Viewer matches Editor however many rows
            // Editor has grown to - counting <tr>s would flip everything after
            // the expanded one.
            expect(shaded(roleRow("Editor"))).toBe(false);
            expect(shaded(roleRow("Regional lead"))).toBe(true);
            expect(shaded(roleRow("Viewer"))).toBe(false);
        });

        it("leaves Carbon's own zebra off", async () => {
            renderPage();
            await chooseUser();

            // It is the thing that miscounts. Turning it back on would put two
            // sets of stripes on the same table, and Carbon's would win the
            // rows it has rules for.
            const table = document
                .querySelector(".role-multi-select-table")!
                .querySelector("table")!;
            expect(table.className).not.toContain("zebra");
        });
    });
});
