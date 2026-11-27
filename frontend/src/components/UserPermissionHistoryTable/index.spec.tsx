import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { PLACE_HOLDER } from "@/constants/constants";
import { NotificationProvider } from "@/context/notification/NotificationProvider";

/**
 * The Comment column: the reason somebody gave when they added, changed or
 * removed a permission, and a placeholder for every row that has none - which
 * includes everything written before comments existed.
 */

const getHistory = vi.fn();

vi.mock("@/services/ApiServiceFactory", () => ({
    AppActlApiService: {
        permissionAuditApi: {
            getPermissionAuditHistoryByUserAndApplication: () => getHistory(),
        },
    },
    AdminMgmtApiService: {
        cssIntegrationsApi: {
            getCssApplicationRoles: () => Promise.resolve({ data: [] }),
        },
    },
}));

const { UserPermissionHistoryTable } = await import("./index");

const auditRow = (overrides: Record<string, unknown> = {}) => ({
    privilege_change_audit_id: "id-1",
    create_date: "2026-11-01T10:00:00",
    create_user: "IDIR\\AAAA1111",
    change_date: "2026-11-01T10:00:00",
    change_performer_user_details: {
        username: "JSMITH",
        first_name: "Jane",
        last_name: "Smith",
    },
    change_performer_user_id: null,
    privilege_change_type_code: "GRANT",
    privilege_change_type_description: "Role added",
    privilege_details: {
        permission_type: "END_USER",
        roles: [{ role: "FREP_VIEWER" }],
    },
    ...overrides,
});

const renderTable = () =>
    render(
        <QueryClientProvider
            client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}
        >
            <NotificationProvider>
                <UserPermissionHistoryTable
                    targetUserGuid="BBBB2222"
                    targetUserType={"I" as never}
                    integrationId={6538}
                    environment="dev"
                />
            </NotificationProvider>
        </QueryClientProvider>
    );

describe("UserPermissionHistoryTable", () => {
    beforeEach(() => {
        getHistory.mockReset();
    });

    it("has a Comment column", async () => {
        getHistory.mockResolvedValue({ data: [auditRow()] });
        renderTable();

        expect(
            await screen.findByRole("columnheader", { name: "Comment" })
        ).toBeInTheDocument();
    });

    it("shows the comment recorded with the change", async () => {
        getHistory.mockResolvedValue({
            data: [auditRow({ comment: "Ticket FAM-123" })],
        });
        renderTable();

        expect(await screen.findByText("Ticket FAM-123")).toBeInTheDocument();
    });

    it("shows a placeholder for a change recorded without one", async () => {
        // Every row written before comments existed reads this way.
        getHistory.mockResolvedValue({ data: [auditRow({ comment: null })] });
        renderTable();

        // Wait for the data row, not the loading skeleton's.
        const performer = await screen.findByText("Jane Smith (JSMITH)");
        const row = performer.closest("tr") as HTMLElement;
        const cells = within(row).getAllByRole("cell");
        expect(cells[cells.length - 1]).toHaveTextContent(PLACE_HOLDER);
    });
});
