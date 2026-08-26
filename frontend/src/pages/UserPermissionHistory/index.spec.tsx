import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
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

vi.mock("@/services/ApiServiceFactory", () => ({
    AppActlApiService: {
        permissionAuditApi: {
            getPermissionAuditHistoryByUserAndApplication: (...args: unknown[]) =>
                getPermissionAuditHistoryByUserAndApplication(...args),
        },
    },
    AdminMgmtApiService: {},
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
                </Routes>
            </MemoryRouter>
            </NotificationProvider>
        </QueryClientProvider>
    );
};

describe("UserPermissionHistory", () => {
    beforeEach(() => {
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
        expect(within(row).getByText("00001012")).toBeInTheDocument();
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
        expect(within(row).getByText("—")).toBeInTheDocument();
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

    it("offers a way back to the table it was reached from", async () => {
        renderPage();

        expect(
            await screen.findByRole("link", { name: "Manage permissions" })
        ).toHaveAttribute("href", "/manage-permissions");
        expect(screen.getByRole("button", { name: "Back" })).toBeInTheDocument();
    });
});
