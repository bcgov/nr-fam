import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { AdminRoleAuthGroup } from "fam-api";

/**
 * Who administers one application, at one tier.
 *
 * The two tiers are the same table with different columns and a different
 * removal call, so most of what is worth asserting is that the tier reaches
 * both.
 */

const getCssApplicationAdministrators = vi.fn();
const deleteCssDelegatedAdmin = vi.fn();
const deleteCssApplicationAdmin = vi.fn();

vi.mock("@/services/ApiServiceFactory", () => ({
    AdminMgmtApiService: {
        cssIntegrationsApi: {
            getCssApplicationAdministrators: () =>
                getCssApplicationAdministrators(),
            deleteCssDelegatedAdmin: (
                integrationId: number,
                environment: string,
                body: unknown
            ) => deleteCssDelegatedAdmin(integrationId, environment, body),
            deleteCssApplicationAdmin: (
                integrationId: number,
                environment: string,
                body: unknown
            ) => deleteCssApplicationAdmin(integrationId, environment, body),
        },
    },
    AppActlApiService: {},
}));

const { AdministratorsTable } = await import("./AdministratorsTable");
const { NotificationProvider } = await import(
    "@/context/notification/NotificationProvider"
);

const DELEGATE = {
    username: "JSMITH",
    user_guid: "AAAA1111",
    domain: "IDIR",
    first_name: "Jane",
    last_name: "Smith",
    email: "jane.smith@gov.bc.ca",
    delegated_role_name: "FREP_EDITOR",
    delegated_role_display_name: "Editor",
    scopes: [{ type: "DISTRICT", value: "DCC", label: "Cariboo-Chilcotin" }],
};

/** CSS knows the username but no GUID, so there is nothing to remove them by. */
const UNIDENTIFIED = {
    username: "GHOST",
    user_guid: null,
    domain: "IDIR",
    delegated_role_name: "FREP_VIEWER",
    delegated_role_display_name: "Viewer",
    scopes: [],
};

const CONFIRM_REMOVE = /Remove$/;

const renderTable = (tier: AdminRoleAuthGroup) => {
    const queryClient = new QueryClient({
        defaultOptions: { queries: { retry: false } },
    });
    return render(
        <QueryClientProvider client={queryClient}>
            <NotificationProvider>
                <AdministratorsTable
                    integrationId={6538}
                    environment="dev"
                    tier={tier}
                    appName="FREP (DEV)"
                />
            </NotificationProvider>
        </QueryClientProvider>
    );
};

const rowFor = async (username: string) => {
    const cell = await screen.findByText(username);
    return cell.closest("tr") as HTMLElement;
};

describe("AdministratorsTable", () => {
    beforeEach(() => {
        getCssApplicationAdministrators
            .mockReset()
            .mockResolvedValue({ data: [DELEGATE] });
        deleteCssDelegatedAdmin.mockReset().mockResolvedValue({ data: {} });
        deleteCssApplicationAdmin.mockReset().mockResolvedValue({ data: {} });
    });

    it("shows what a delegated administrator may grant, and where", async () => {
        renderTable("DELEGATED_ADMIN");

        const row = await rowFor("JSMITH");
        expect(within(row).getByText("Editor")).toBeInTheDocument();
        expect(within(row).getByText("Cariboo-Chilcotin")).toBeInTheDocument();
    });

    it("omits the role and scope columns for an application administrator", async () => {
        // They are authorised over the application rather than over any one of
        // its roles, so the columns would be permanently blank.
        renderTable("APP_ADMIN");
        await rowFor("JSMITH");

        expect(screen.queryByText("May grant")).not.toBeInTheDocument();
        expect(screen.queryByText("Scope")).not.toBeInTheDocument();
    });

    it("removes a delegation by its base role and this row's scopes", async () => {
        renderTable("DELEGATED_ADMIN");
        const row = await rowFor("JSMITH");

        await userEvent.click(
            within(row).getByRole("button", { name: "Remove administrator" })
        );
        await userEvent.click(
            await screen.findByRole("button", { name: CONFIRM_REMOVE })
        );

        await waitFor(() =>
            expect(deleteCssDelegatedAdmin).toHaveBeenCalledTimes(1)
        );
        // Together these rebuild exactly the delegation role the row came from.
        expect(deleteCssDelegatedAdmin.mock.calls[0][2]).toMatchObject({
            user_guid: "AAAA1111",
            user_type: "IDIR",
            role_name: "FREP_EDITOR",
            scopes: [{ type: "DISTRICT", values: ["DCC"] }],
        });
        expect(deleteCssApplicationAdmin).not.toHaveBeenCalled();
    });

    it("removes an application administrator with no role and no scope", async () => {
        renderTable("APP_ADMIN");
        const row = await rowFor("JSMITH");

        await userEvent.click(
            within(row).getByRole("button", { name: "Remove administrator" })
        );
        await userEvent.click(
            await screen.findByRole("button", { name: CONFIRM_REMOVE })
        );

        await waitFor(() =>
            expect(deleteCssApplicationAdmin).toHaveBeenCalledTimes(1)
        );
        expect(deleteCssApplicationAdmin.mock.calls[0][2]).toEqual({
            user_guid: "AAAA1111",
            user_type: "IDIR",
        });
        expect(deleteCssDelegatedAdmin).not.toHaveBeenCalled();
    });

    it("words the confirmation for what each tier actually loses", async () => {
        renderTable("DELEGATED_ADMIN");
        const row = await rowFor("JSMITH");

        await userEvent.click(
            within(row).getByRole("button", { name: "Remove administrator" })
        );

        // A delegated administrator loses the right to grant one role and keeps
        // the others; saying "removed as an administrator" would overstate it.
        const message = await screen.findByText(/Are you sure/);
        expect(message.textContent).toContain("from granting");
        expect(message.textContent).toContain("Editor");
        expect(message.textContent).toContain("keep any other roles");
    });

    it("words it differently for an application administrator", async () => {
        renderTable("APP_ADMIN");
        const row = await rowFor("JSMITH");

        await userEvent.click(
            within(row).getByRole("button", { name: "Remove administrator" })
        );

        const message = await screen.findByText(/Are you sure/);
        expect(message.textContent).toContain("application administrator");
        expect(message.textContent).toContain("appointing other administrators");
        expect(message.textContent).not.toContain("from granting");
    });

    it("disables removal for a row it cannot identify", async () => {
        // Disabled rather than hidden, so the row does not look ordinary while
        // its button quietly fails.
        getCssApplicationAdministrators.mockResolvedValue({
            data: [DELEGATE, UNIDENTIFIED],
        });
        renderTable("DELEGATED_ADMIN");

        const ghost = await rowFor("GHOST");
        const removable = await rowFor("JSMITH");
        expect(
            within(ghost).getByRole("button", { name: "Remove administrator" })
        ).toBeDisabled();
        expect(
            within(removable).getByRole("button", { name: "Remove administrator" })
        ).toBeEnabled();
    });

    it("says so out loud when a delegation is withdrawn", async () => {
        renderTable("DELEGATED_ADMIN");
        const row = await rowFor("JSMITH");

        await userEvent.click(
            within(row).getByRole("button", { name: "Remove administrator" })
        );
        await userEvent.click(
            await screen.findByRole("button", { name: CONFIRM_REMOVE })
        );

        const toast = await screen.findByRole("status");
        expect(within(toast).getByText("Delegated admin removed")).toBeInTheDocument();
        expect(toast.textContent).toContain("can no longer grant Editor");
    });

    it("reports the backend's own reason when the load fails", async () => {
        // A generic line hid the actual cause - a missing CSS_OWN_INTEGRATION_ID,
        // or a refusal - behind "please try again", advice that would not have
        // helped in either case.
        getCssApplicationAdministrators.mockRejectedValue({
            response: { data: { description: "CSS_OWN_INTEGRATION_ID is not set." } },
        });
        renderTable("APP_ADMIN");

        expect(
            await screen.findByText("CSS_OWN_INTEGRATION_ID is not set.")
        ).toBeInTheDocument();
    });

    it("says the application has no administrators rather than nothing at all", async () => {
        getCssApplicationAdministrators.mockResolvedValue({ data: [] });
        renderTable("APP_ADMIN");

        expect(
            await screen.findByText("FREP (DEV) has no administrators at this level")
        ).toBeInTheDocument();
    });
});
