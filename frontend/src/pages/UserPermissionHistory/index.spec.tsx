import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { NotificationProvider } from "@/context/notification/NotificationProvider";

/**
 * One user's permission history for one application.
 *
 * The trail is FAM's own: CSS records who holds what and nothing about how it
 * came to be that way, so this is the only place a grant or revocation is
 * recorded at all. It is keyed on GUID and directory together, because the two
 * directories number their people separately.
 */

const getPermissionAuditHistoryByUserAndApplication = vi.fn();
const getCssApplicationRoles = vi.fn();

vi.mock("@/services/ApiServiceFactory", () => ({
    AppActlApiService: {
        permissionAuditApi: {
            getPermissionAuditHistoryByUserAndApplication: (...args: unknown[]) =>
                getPermissionAuditHistoryByUserAndApplication(...args),
        },
    },
    AdminMgmtApiService: {
        cssIntegrationsApi: {
            getCssApplicationRoles: () => getCssApplicationRoles(),
        },
    },
}));

const { UserPermissionHistory } = await import("./index");

const GRANTED = {
    change_date: "2026-01-15T18:30:00Z",
    privilege_change_type_description: "Permission granted",
    change_performer_user_details: {
        username: "ADMINUSER",
        first_name: "Alex",
        last_name: "Nguyen",
    },
    privilege_details: {
        roles: [
            {
                role: "FREP_EDITOR",
                scopes: [{ client_id: "00001012", client_name: "Timber Co" }],
            },
        ],
    },
};

const SYSTEM_CHANGE = {
    change_date: "2026-01-16T09:00:00Z",
    privilege_change_type_description: "Permission revoked",
    change_performer_user_details: null,
    privilege_details: { roles: [{ role: "FREP_VIEWER", scopes: [] }] },
};

const renderPage = (
    query = "?targetUserGuid=AAAA1111&targetUserType=IDIR&integrationId=6538&environment=dev&userName=JSMITH"
) => {
    const queryClient = new QueryClient({
        defaultOptions: { queries: { retry: false } },
    });
    render(
        <QueryClientProvider client={queryClient}>
            <NotificationProvider>
            <MemoryRouter initialEntries={[`/permission-history${query}`]}>
                <Routes>
                    <Route
                        path="/permission-history"
                        element={<UserPermissionHistory />}
                    />
                    <Route
                        path="/manage-permissions"
                        element={<p>Manage permissions landed</p>}
                    />
                </Routes>
            </MemoryRouter>
            </NotificationProvider>
        </QueryClientProvider>
    );
};

describe("UserPermissionHistory", () => {
    beforeEach(() => {
        // No role list by default, so every test but the fallback one proves
        // what the row itself carries.
        getCssApplicationRoles.mockReset().mockResolvedValue({ data: [] });
        getPermissionAuditHistoryByUserAndApplication
            .mockReset()
            .mockResolvedValue({ data: [GRANTED, SYSTEM_CHANGE] });
    });

    it("names whose history this is", async () => {
        renderPage();

        expect(
            await screen.findByText("Check JSMITH's permission history")
        ).toBeInTheDocument();
    });

    it("asks by GUID and directory together", async () => {
        // The same GUID may exist in both directories, so the GUID alone does
        // not identify a row.
        renderPage();

        await waitFor(() =>
            expect(
                getPermissionAuditHistoryByUserAndApplication
            ).toHaveBeenCalledWith("AAAA1111", "IDIR", 6538, "dev")
        );
    });

    it("passes the BCeID directory through when that is the target", async () => {
        renderPage(
            "?targetUserGuid=BBBB2222&targetUserType=BCEID_BUS&integrationId=6538&environment=dev&userName=CONTRACTOR"
        );

        await waitFor(() =>
            expect(
                getPermissionAuditHistoryByUserAndApplication
            ).toHaveBeenCalledWith("BBBB2222", "BCEID_BUS", 6538, "dev")
        );
    });

    it("shows the role and its scope for each change", async () => {
        renderPage();

        const row = (await screen.findByText("Permission granted")).closest(
            "tr"
        ) as HTMLElement;
        expect(within(row).getByText("FREP_EDITOR")).toBeInTheDocument();
        // Prefixed, because a bare "00001012" does not say whether it is an
        // organisation, a district or a region.
        expect(within(row).getByText("Organization: 00001012")).toBeInTheDocument();
    });

    it("labels each scope by what kind it is", async () => {
        // A district code, a region name and an organisation number are three
        // different things that all look like "a value". Unprefixed, the column
        // leaves the reader working out which is which.
        getPermissionAuditHistoryByUserAndApplication.mockResolvedValue({
            data: [
                {
                    ...GRANTED,
                    privilege_details: {
                        roles: [
                            {
                                role: "FREP_EDITOR",
                                role_display_name: "Editor",
                                scopes: [
                                    { scope_type: "District", client_id: "DCC" },
                                    {
                                        scope_type: "Region",
                                        client_id: "KOOTENAY_BOUNDARY",
                                        client_name: "Kootenay-Boundary",
                                    },
                                    { scope_type: "Client", client_id: "00001012" },
                                ],
                            },
                        ],
                    },
                },
            ],
        });
        renderPage();

        const row = (await screen.findByText("Permission granted")).closest(
            "tr"
        ) as HTMLElement;

        expect(within(row).getByText("District: DCC")).toBeInTheDocument();
        // The name, not the code - it is what the row recorded at the time.
        expect(within(row).getByText("Region: Kootenay-Boundary")).toBeInTheDocument();
        expect(within(row).getByText("Organization: 00001012")).toBeInTheDocument();
    });

    it("shows the role by the name people know it by", async () => {
        getPermissionAuditHistoryByUserAndApplication.mockResolvedValue({
            data: [
                {
                    ...GRANTED,
                    privilege_details: {
                        roles: [
                            {
                                role: "FREP_EDITOR",
                                role_display_name: "Editor",
                                scopes: [],
                            },
                        ],
                    },
                },
            ],
        });
        renderPage();

        const row = (await screen.findByText("Permission granted")).closest(
            "tr"
        ) as HTMLElement;

        expect(within(row).getByText("Editor")).toBeInTheDocument();
        expect(within(row).queryByText("FREP_EDITOR")).not.toBeInTheDocument();
    });

    it("names the role from the application's own list when the row has none", async () => {
        /*
            The row carries a name only from a backend that resolves one, which
            is newer than some of the history it serves. The application's role
            list says the same thing and is usually already cached from the
            grant screen, so the pill does not have to wait for a deployment to
            stop showing a code.
        */
        getCssApplicationRoles.mockResolvedValue({
            data: [{ name: "FREP_EDITOR", display_name: "Editor" }],
        });
        renderPage();

        const row = (await screen.findByText("Permission granted")).closest(
            "tr"
        ) as HTMLElement;

        expect(await within(row).findByText("Editor")).toBeInTheDocument();
        expect(within(row).queryByText("FREP_EDITOR")).not.toBeInTheDocument();
    });

    it("prefers the name the row itself carries", async () => {
        // The row's name was resolved when the history was read; the list is
        // only a fallback, so a disagreement is settled in the row's favour.
        getCssApplicationRoles.mockResolvedValue({
            data: [{ name: "FREP_EDITOR", display_name: "From the list" }],
        });
        getPermissionAuditHistoryByUserAndApplication.mockResolvedValue({
            data: [
                {
                    ...GRANTED,
                    privilege_details: {
                        roles: [
                            {
                                role: "FREP_EDITOR",
                                role_display_name: "From the row",
                                scopes: [],
                            },
                        ],
                    },
                },
            ],
        });
        renderPage();

        expect(await screen.findByText("From the row")).toBeInTheDocument();
        expect(screen.queryByText("From the list")).not.toBeInTheDocument();
    });

    it("falls back to the code when nothing knows the name", async () => {
        // A role deleted since, or one that never had a label sidecar. The code
        // is what is left, and it reads perfectly well.
        renderPage();

        const row = (await screen.findByText("Permission granted")).closest(
            "tr"
        ) as HTMLElement;

        expect(within(row).getByText("FREP_EDITOR")).toBeInTheDocument();
    });

    it("names who made the change", async () => {
        renderPage();

        const row = (await screen.findByText("Permission granted")).closest(
            "tr"
        ) as HTMLElement;
        expect(within(row).getByText("Alex Nguyen (ADMINUSER)")).toBeInTheDocument();
    });

    it("marks a change nobody performed rather than leaving it blank", async () => {
        renderPage();

        const row = (await screen.findByText("Permission revoked")).closest(
            "tr"
        ) as HTMLElement;
        // Twice over: nobody performed it, and it was scoped to nothing. Both
        // are absences worth marking rather than leaving as empty cells.
        expect(within(row).getAllByText("—")).toHaveLength(2);
    });

    it("says so when there is no history", async () => {
        getPermissionAuditHistoryByUserAndApplication.mockResolvedValue({
            data: [],
        });
        renderPage();

        expect(
            await screen.findByText("No User Permissions History found.")
        ).toBeInTheDocument();
    });

    it("reports a failure rather than showing an empty trail", async () => {
        // An empty table would say "this person's access was never changed",
        // which is a different and much stronger claim than "we could not ask".
        getPermissionAuditHistoryByUserAndApplication.mockRejectedValue(
            new Error("audit is down")
        );
        renderPage();

        expect(
            await screen.findByText("Failed to fetch the permission history")
        ).toBeInTheDocument();
        expect(
            screen.queryByText("No User Permissions History found.")
        ).not.toBeInTheDocument();
    });

    it("offers one way back, at the top where it is looked for", async () => {
        renderPage();

        /*
            One control, not three. There used to be a breadcrumb above the
            title and a Back button below the table, both going to the same
            place - and the button sat past however many rows of history the
            person had just scrolled through.
        */
        const back = await screen.findByRole("button", {
            name: /Back to Manage permissions/,
        });
        expect(back).toBeInTheDocument();
        expect(
            screen.queryByRole("link", { name: "Manage permissions" })
        ).not.toBeInTheDocument();
        expect(screen.queryByRole("button", { name: "Back" })).not.toBeInTheDocument();

        await userEvent.click(back);
        expect(
            await screen.findByText("Manage permissions landed")
        ).toBeInTheDocument();
    });
});
