import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { AuthContext, type AuthContextValue } from "@/context/auth/AuthContext";
import { NotificationProvider } from "@/context/notification/NotificationProvider";

/**
 * Appointing an application administrator.
 *
 * <p>One step - the authority is over the application, so there is no role to
 * choose and no scope to narrow. Who may hold it is the part worth pinning: they
 * grant every role the application defines and appoint delegated administrators,
 * which is authority over the application itself.
 */

const searchIdirUsers = vi.fn();
const createCssApplicationAdmin = vi.fn();
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
            createCssApplicationAdmin: (
                integrationId: number,
                environment: string,
                request: unknown
            ) => createCssApplicationAdmin(integrationId, environment, request),
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

const { AddApplicationAdmin } = await import("./index");
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
        role_name: "APP_ADMIN_6538_DEV",
        role_created: false,
        assigned: true,
        email_sending_status: "NOT_REQUIRED",
    },
};

/** The same shape when CSS refused it, which still arrives with a 200. */
const refused = (message: string) => ({
    data: {
        role_name: "APP_ADMIN_6538_DEV",
        role_created: true,
        assigned: false,
        error_message: message,
        email_sending_status: "NOT_REQUIRED",
    },
});

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
                initialEntries={[`/manage-permissions/add-application-admin${search}`]}
            >
                <NotificationProvider>
                    <SelectedAppProvider>
                        <Routes>
                            <Route
                                path="/manage-permissions/add-application-admin"
                                element={<AddApplicationAdmin />}
                            />
                        </Routes>
                    </SelectedAppProvider>
                </NotificationProvider>
            </MemoryRouter>
          </AuthContext.Provider>
        </QueryClientProvider>
    );
};

/** Search, then confirm the one result - the whole of this form's first step. */
const chooseUser = async () => {
    await userEvent.type(screen.getByRole("textbox"), "smith");
    await userEvent.click(screen.getByRole("button", { name: "Search users" }));
    const dialog = await screen.findByRole("dialog");
    await userEvent.click(
        within(dialog).getByRole("button", { name: "Confirm" })
    );
};

describe("AddApplicationAdmin", () => {
    beforeEach(() => {
        searchIdirUsers.mockReset().mockResolvedValue({ data: IDIR_RESULT });
        createCssApplicationAdmin.mockReset().mockResolvedValue(assigned);
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
            screen.getByRole("button", { name: "Add application admin" })
        );

        await waitFor(() => expect(createCssApplicationAdmin).toHaveBeenCalled());
        expect(createCssApplicationAdmin.mock.calls[0][2]).toMatchObject({
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
        createCssApplicationAdmin.mockResolvedValue(
            refused("could not verify user with the upstream identity provider")
        );
        renderPage();
        await chooseUser();
        await userEvent.click(
            screen.getByRole("button", { name: "Add application admin" })
        );

        expect(
            await screen.findByText(/could not verify user/)
        ).toBeInTheDocument();
        expect(navigate).not.toHaveBeenCalled();
    });

    it("returns to the tab it was opened from", async () => {
        renderPage("?integrationId=6538&environment=dev&tab=app-admins");
        await chooseUser();
        await userEvent.click(
            screen.getByRole("button", { name: "Add application admin" })
        );

        await waitFor(() =>
            expect(navigate).toHaveBeenCalledWith("/manage-permissions?tab=app-admins")
        );
    });

    it("says where the tier stops", async () => {
        // They cannot create or delete roles - that is a DevOps administrator's,
        // and the difference is easy to assume the other way.
        renderPage();

        expect(
            screen.getByText(/cannot create or delete roles/)
        ).toBeInTheDocument();
    });
});
