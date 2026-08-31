import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { AuthContext, type AuthContextValue } from "@/context/auth/AuthContext";
import { NotificationProvider } from "@/context/notification/NotificationProvider";

/**
 * Appointing a DevOps administrator.
 *
 * <p>One step - the authority is over the application, so there is no role to
 * choose and no scope to narrow. What is worth pinning is who may hold it: a
 * DevOps administrator decides what an application's roles are, which is
 * authority over the application itself rather than over work done in it.
 */

const searchIdirUsers = vi.fn();
const createCssDevopsAdmin = vi.fn();
const navigate = vi.fn();

vi.mock("@/services/ApiServiceFactory", () => ({
    AppActlApiService: {
        idirBceidProxyApi: {
            searchIdirUsers: () => searchIdirUsers(),
            bceidLookup: () => Promise.resolve({ data: { found: false } }),
        },
    },
    AdminMgmtApiService: {
        cssIntegrationsApi: {
            createCssDevopsAdmin: (
                integrationId: number,
                environment: string,
                request: unknown
            ) => createCssDevopsAdmin(integrationId, environment, request),
            getCssApplications: () => Promise.resolve({ data: [] }),
        },
    },
}));

vi.mock("react-router-dom", async () => {
    const actual = await vi.importActual<typeof import("react-router-dom")>(
        "react-router-dom"
    );
    return { ...actual, useNavigate: () => navigate };
});

const { AddDevopsAdmin } = await import("./index");
const { SelectedAppProvider } = await import(
    "@/context/application/SelectedAppProvider"
);

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

/** What the endpoint answers on a clean appointment. */
const assigned = {
    data: {
        role_name: "DEVOPS_ADMIN_6538_DEV",
        role_created: false,
        assigned: true,
        email_sending_status: "NOT_REQUIRED",
    },
};

/** The same shape when CSS refused it, which still arrives with a 200. */
const refused = (message: string) => ({
    data: {
        role_name: "DEVOPS_ADMIN_6538_DEV",
        role_created: true,
        assigned: false,
        error_message: message,
        email_sending_status: "NOT_REQUIRED",
    },
});

/** Search, then confirm the one result - the whole of this form's first step. */
const chooseUser = async () => {
    await userEvent.type(screen.getByRole("textbox"), "smith");
    await userEvent.click(screen.getByRole("button", { name: "Search users" }));
    const dialog = await screen.findByRole("dialog");
    await userEvent.click(
        within(dialog).getByRole("button", { name: "Confirm" })
    );
};

const renderPage = (search = "?integrationId=6538&environment=dev") => {
    // UserSearch reads the signed-in user, to refuse a search for themselves.
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
                initialEntries={[`/manage-permissions/add-devops-admin${search}`]}
            >
                <NotificationProvider>
                    <SelectedAppProvider>
                        <Routes>
                            <Route
                                path="/manage-permissions/add-devops-admin"
                                element={<AddDevopsAdmin />}
                            />
                        </Routes>
                    </SelectedAppProvider>
                </NotificationProvider>
            </MemoryRouter>
          </AuthContext.Provider>
        </QueryClientProvider>
    );
};

describe("AddDevopsAdmin", () => {
    beforeEach(() => {
        searchIdirUsers.mockReset().mockResolvedValue({ data: IDIR_RESULT });
        createCssDevopsAdmin.mockReset().mockResolvedValue(assigned);
        navigate.mockReset();
    });

    it("offers IDIR alone, and does not offer the choice", async () => {
        /*
            The backend refuses anybody else, so offering Business BCeID would
            be offering a search whose every result is unusable. One domain, so
            the selector is disabled rather than being a list of one.
        */
        renderPage();

        const domain = screen.getByLabelText("User domain") as HTMLSelectElement;
        expect(within(domain).getAllByRole("option")).toHaveLength(1);
        expect(within(domain).getByRole("option")).toHaveValue("IDIR");
        expect(domain).toBeDisabled();
    });

    it("appoints the chosen person as an IDIR user", async () => {
        renderPage();

        await userEvent.type(screen.getByRole("textbox"), "smith");
        await userEvent.click(screen.getByRole("button", { name: "Search users" }));
        const dialog = await screen.findByRole("dialog");
        await userEvent.click(
            within(dialog).getByRole("button", { name: "Confirm" })
        );

        await userEvent.click(
            screen.getByRole("button", { name: "Add DevOps admin" })
        );

        await waitFor(() => expect(createCssDevopsAdmin).toHaveBeenCalled());
        expect(createCssDevopsAdmin.mock.calls[0][2]).toMatchObject({
            user_guid: "AAAA1111",
            user_type: "IDIR",
        });
    });

    it("does not announce an appointment CSS refused in a 200", async () => {
        /*
            The endpoint answers 200 with assigned:false when CSS declines the
            assignment - the role was created, the person was not given it.
            Reading only the status announced administrators who then were not in
            the table, with nothing said about why.
        */
        createCssDevopsAdmin.mockResolvedValue(
            refused("could not verify user with the upstream identity provider")
        );
        renderPage();
        await chooseUser();
        await userEvent.click(
            screen.getByRole("button", { name: "Add DevOps admin" })
        );

        expect(
            await screen.findByText(/could not verify user/)
        ).toBeInTheDocument();
        expect(navigate).not.toHaveBeenCalled();
    });

    it("returns to the tab it was opened from", async () => {
        renderPage("?integrationId=6538&environment=dev&tab=devops");
        await chooseUser();
        await userEvent.click(
            screen.getByRole("button", { name: "Add DevOps admin" })
        );

        await waitFor(() =>
            expect(navigate).toHaveBeenCalledWith("/manage-permissions?tab=devops")
        );
    });

    it("says what the tier does, since it is not the one people expect", async () => {
        // It grants nobody anything - which is the thing worth saying before
        // somebody appoints one expecting an application administrator.
        renderPage();

        expect(
            screen.getByText(/define and remove the roles of this application/)
        ).toBeInTheDocument();
        expect(
            screen.getByText(/cannot grant anybody access/)
        ).toBeInTheDocument();
    });
});
