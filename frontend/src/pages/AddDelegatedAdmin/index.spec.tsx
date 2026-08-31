import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { AuthContext, type AuthContextValue } from "@/context/auth/AuthContext";

/**
 * Appointing a delegated administrator.
 *
 * The same shape as the grant form asking a different question: the roles here
 * are what the appointee may <em>hand out</em>, and the scope is what they may
 * hand it out for. What is worth asserting is the difference - one person, the
 * delegation wording, and a per-role failure not discarding the rest.
 */

const searchIdirUsers = vi.fn();
const getCssApplicationRoles = vi.fn();
const createCssDelegatedAdmin = vi.fn();
const getDistricts = vi.fn();
const navigate = vi.fn();

vi.mock("@/services/ApiServiceFactory", () => ({
    AppActlApiService: {
        idirBceidProxyApi: {
            searchIdirUsers: () => searchIdirUsers(),
            bceidLookup: () => Promise.resolve({ data: { found: false } }),
        },
        districtsApi: { getDistricts: () => getDistricts() },
        forestClientsApi: {
            autocompleteForestClients: () => Promise.resolve({ data: [] }),
        },
    },
    AdminMgmtApiService: {
        cssIntegrationsApi: {
            getCssApplicationRoles: () => getCssApplicationRoles(),
            createCssDelegatedAdmin: (
                integrationId: number,
                environment: string,
                request: unknown
            ) => createCssDelegatedAdmin(integrationId, environment, request),
        },
    },
}));

vi.mock("react-router-dom", async () => {
    const actual = await vi.importActual<typeof import("react-router-dom")>(
        "react-router-dom"
    );
    return { ...actual, useNavigate: () => navigate };
});

const { AddDelegatedAdmin } = await import("./index");
const { SelectedAppProvider } = await import(
    "@/context/application/SelectedAppProvider"
);
const { NotificationProvider } = await import(
    "@/context/notification/NotificationProvider"
);

const VIEWER = {
    name: "FREP_VIEWER",
    display_name: "Viewer",
    role_type_district: false,
    role_type_client: false,
};

const EDITOR = {
    name: "FREP_EDITOR",
    display_name: "Editor",
    role_type_district: true,
    role_type_client: false,
};

/** One delegation, assigned - what the endpoint answers on a clean appointment. */
const assigned = (roleName: string) => ({
    data: [
        {
            role_name: roleName,
            role_created: false,
            assigned: true,
            email_sending_status: "NOT_REQUIRED",
        },
    ],
});

/** The same shape when CSS refused it, which still arrives with a 200. */
const refused = (roleName: string, message: string) => ({
    data: [
        {
            role_name: roleName,
            role_created: true,
            assigned: false,
            error_message: message,
            email_sending_status: "NOT_REQUIRED",
        },
    ],
});

const renderPage = (search = "?integrationId=6538&environment=dev") => {
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
    render(
        <QueryClientProvider client={queryClient}>
            <AuthContext.Provider value={auth}>
                <MemoryRouter
                    initialEntries={[
                        `/manage-permissions/add-delegated-admin${search}`,
                    ]}
                >
                    <NotificationProvider>
                        <SelectedAppProvider>
                            <Routes>
                                <Route
                                    path="/manage-permissions/add-delegated-admin"
                                    element={<AddDelegatedAdmin />}
                                />
                            </Routes>
                        </SelectedAppProvider>
                    </NotificationProvider>
                </MemoryRouter>
            </AuthContext.Provider>
        </QueryClientProvider>
    );
};

const chooseUser = async () => {
    await userEvent.type(screen.getByRole("textbox"), "smith");
    await userEvent.click(screen.getByRole("button", { name: "Search users" }));
    const dialog = await screen.findByRole("dialog");
    await userEvent.click(within(dialog).getByRole("button", { name: "Confirm" }));
    await waitFor(() =>
        expect(
            screen.getByRole("heading", { name: "Select the roles they may grant" })
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

/** The open scope panel inside a role's own row. */
const scopePanel = () =>
    document.querySelector(".role-scope-fields") as HTMLElement;


describe("AddDelegatedAdmin", () => {
    beforeEach(() => {
        searchIdirUsers.mockReset().mockResolvedValue({
            data: {
                items: [
                    {
                        userId: "JSMITH",
                        guid: "AAAA1111",
                        firstName: "Jane",
                        lastName: "Smith",
                    },
                ],
            },
        });
        getCssApplicationRoles
            .mockReset()
            .mockResolvedValue({ data: [VIEWER, EDITOR] });
        createCssDelegatedAdmin
            .mockReset()
            .mockResolvedValue(assigned("DELEGATED_ADMIN_6538_DEV__FREP_VIEWER"));
        getDistricts.mockReset().mockResolvedValue({
            data: [
                { org_unit_code: "DCC", org_unit_name: "Cariboo", expired: false },
            ],
        });
        navigate.mockReset();
    });

    it("appoints one person at a time", async () => {
        // Appointing is rarer and more consequential than granting, and the
        // confirmation reads better naming one person.
        renderPage();
        await userEvent.type(screen.getByRole("textbox"), "smith");
        await userEvent.click(
            screen.getByRole("button", { name: "Search users" })
        );

        const dialog = await screen.findByRole("dialog");
        expect(within(dialog).getByLabelText("Select JSMITH")).toHaveAttribute(
            "type",
            "radio"
        );
    });

    it("withholds the role step until somebody is chosen", async () => {
        renderPage();

        expect(
            screen.queryByRole("heading", {
                name: "Select the roles they may grant",
            })
        ).not.toBeInTheDocument();

        await chooseUser();

        expect(
            screen.getByRole("heading", {
                name: "Select the roles they may grant",
            })
        ).toBeInTheDocument();
    });

    it("counts delegations, not permissions", async () => {
        // Same arithmetic, different noun: this is what the appointee may hand
        // out, not what they are being given.
        renderPage();
        await chooseUser();
        await tickRole("Editor");

        await pickScope("District", "Cariboo");

        await waitFor(() =>
            expect(screen.getByText(/This will create/).textContent).toContain(
                "delegation"
            )
        );
    });

    it("sends one delegation per role, carrying that role's scope", async () => {
        renderPage();
        await chooseUser();
        await tickRole("Viewer");
        await tickRole("Editor");

        await pickScope("District", "Cariboo");
        await userEvent.click(
            screen.getByRole("button", { name: "Add delegated admin" })
        );

        await waitFor(() =>
            expect(createCssDelegatedAdmin).toHaveBeenCalledTimes(2)
        );
        expect(
            createCssDelegatedAdmin.mock.calls.map((call) => call[2])
        ).toEqual([
            expect.objectContaining({
                user_guid: "AAAA1111",
                role_name: "FREP_VIEWER",
                scopes: [],
            }),
            expect.objectContaining({
                user_guid: "AAAA1111",
                role_name: "FREP_EDITOR",
                scopes: [{ type: "DISTRICT", values: ["DCC"] }],
            }),
        ]);
    });

    it("refuses to appoint with a scoped role left empty", async () => {
        renderPage();
        await chooseUser();
        await tickRole("Editor");

        await userEvent.click(
            screen.getByRole("button", { name: "Add delegated admin" })
        );

        expect(
            await screen.findByText("Choose at least one district for this role")
        ).toBeInTheDocument();
        expect(createCssDelegatedAdmin).not.toHaveBeenCalled();
    });

    it("keeps the delegations that landed when another is refused", async () => {
        // They have happened in CSS and cannot be taken back by throwing here.
        createCssDelegatedAdmin
            .mockResolvedValueOnce(assigned("DELEGATED_ADMIN_6538_DEV__FREP_VIEWER"))
            .mockRejectedValueOnce({
                response: { data: { detail: { description: "Not yours to delegate" } } },
            });
        renderPage();
        await chooseUser();
        await tickRole("Viewer");
        await tickRole("Editor");

        await pickScope("District", "Cariboo");
        await userEvent.click(
            screen.getByRole("button", { name: "Add delegated admin" })
        );

        const toast = await screen.findByRole("status");
        expect(
            within(toast).getByText("Some roles were not delegated")
        ).toBeInTheDocument();
        expect(toast.textContent).toContain("1 could not be delegated");
        await waitFor(() =>
            expect(navigate).toHaveBeenCalledWith("/manage-permissions")
        );
    });

    it("stays on the screen and says why when nothing landed", async () => {
        // Nothing to confirm, and nowhere better to explain it - the form is
        // still filled in.
        createCssDelegatedAdmin.mockRejectedValue({
            response: { data: { detail: { description: "Not yours to delegate" } } },
        });
        renderPage();
        await chooseUser();
        await tickRole("Viewer");
        await userEvent.click(
            screen.getByRole("button", { name: "Add delegated admin" })
        );

        expect(
            await screen.findByText(/Not yours to delegate/)
        ).toBeInTheDocument();
        expect(navigate).not.toHaveBeenCalled();
    });

    it("announces a clean appointment", async () => {
        renderPage();
        await chooseUser();
        await tickRole("Viewer");
        await userEvent.click(
            screen.getByRole("button", { name: "Add delegated admin" })
        );

        const toast = await screen.findByRole("status");
        expect(
            within(toast).getByText("Delegated admin added")
        ).toBeInTheDocument();
        expect(toast.textContent).toContain("JSMITH can now grant 1 role");
    });

    it("does not announce an appointment CSS refused in a 200", async () => {
        /*
            The endpoint answers 200 with assigned:false when CSS declines the
            assignment - the delegation role was created, the person was not
            given it. Counting the call itself as success announced delegated
            administrators who then were not in the table, with nothing said
            about why.
        */
        createCssDelegatedAdmin.mockResolvedValue(
            refused(
                "DELEGATED_ADMIN_6538_DEV__FREP_VIEWER",
                "could not verify user with the upstream identity provider"
            )
        );
        renderPage();
        await chooseUser();
        await tickRole("Viewer");
        await userEvent.click(
            screen.getByRole("button", { name: "Add delegated admin" })
        );

        expect(
            await screen.findByText(/could not verify user/)
        ).toBeInTheDocument();
        expect(navigate).not.toHaveBeenCalled();
    });

    it("returns to the tab it was opened from", async () => {
        renderPage("?integrationId=6538&environment=dev&tab=delegated");
        await chooseUser();
        await tickRole("Viewer");
        await userEvent.click(
            screen.getByRole("button", { name: "Add delegated admin" })
        );

        await waitFor(() =>
            expect(navigate).toHaveBeenCalledWith(
                "/manage-permissions?tab=delegated"
            )
        );
    });
});
