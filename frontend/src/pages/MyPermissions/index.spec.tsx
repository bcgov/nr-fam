import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { NotificationProvider } from "@/context/notification/NotificationProvider";

/**
 * What the signed-in user holds, in two parts.
 *
 * They are two queries on purpose: what somebody may administer is on their
 * token and is immediate, while what they hold as a user of each application
 * lives in CSS and takes one request per integration. The test that matters is
 * that the fast half does not wait for the slow one.
 */

const fetchSelfPermissions = vi.fn();
const fetchSelfApplicationRoles = vi.fn();

vi.mock("@/services/AuthApiService", () => ({
    fetchSelfPermissions: () => fetchSelfPermissions(),
    fetchSelfApplicationRoles: () => fetchSelfApplicationRoles(),
}));

const { MyPermissions } = await import("./index");

const APP_ROLE = {
    application_name: "FREP",
    environment: "dev",
    base_role_name: "FREP_EDITOR",
    role_display_name: "Editor",
    role_description: "Edit within a district",
    scopes: [{ type: "DISTRICT", value: "DCC", label: "Cariboo-Chilcotin" }],
};

const UNSCOPED_ROLE = {
    application_name: "FOM",
    environment: "prod",
    base_role_name: "FOM_VIEWER",
    role_display_name: "Viewer",
    scopes: [],
};

const APP_ADMIN = {
    role: "APP_ADMIN",
    role_description: "Application administrator",
    application_name: "FREP",
    environment: "dev",
};

const FAM_ADMIN = {
    role: "FAM_ADMIN",
    role_description: "FAM administrator",
    application_name: "Forests Access Management",
    environment: null,
};

const renderPage = () => {
    const queryClient = new QueryClient({
        defaultOptions: { queries: { retry: false } },
    });
    return render(
        <QueryClientProvider client={queryClient}>
            <NotificationProvider>
            <MyPermissions />
            </NotificationProvider>
        </QueryClientProvider>
    );
};

const sectionFor = (heading: string) =>
    screen.getByRole("heading", { name: heading }).closest(
        ".step-container"
    ) as HTMLElement;

describe("MyPermissions", () => {
    beforeEach(() => {
        fetchSelfPermissions.mockReset().mockResolvedValue([APP_ADMIN, FAM_ADMIN]);
        fetchSelfApplicationRoles
            .mockReset()
            .mockResolvedValue([APP_ROLE, UNSCOPED_ROLE]);
    });

    it("asks the server rather than reading the token", async () => {
        // FAM resolves roles per request so a revocation takes effect without a
        // fresh sign-in. If this stopped calling, nothing else would show it.
        renderPage();

        await waitFor(() => {
            expect(fetchSelfPermissions).toHaveBeenCalled();
            expect(fetchSelfApplicationRoles).toHaveBeenCalled();
        });
    });

    it("shows a role's scope by its resolved name", async () => {
        renderPage();

        const row = (await screen.findByText("Editor")).closest(
            "tr"
        ) as HTMLElement;
        expect(within(row).getByText("Cariboo-Chilcotin")).toBeInTheDocument();
        expect(within(row).getByText("DEV")).toBeInTheDocument();
    });

    it("marks an unscoped role rather than leaving the cell blank", async () => {
        renderPage();

        const row = (await screen.findByText("Viewer")).closest(
            "tr"
        ) as HTMLElement;
        expect(within(row).getAllByText("—").length).toBeGreaterThan(0);
    });

    it("leaves the environment blank for a role that spans every one", async () => {
        // FAM_ADMIN names no application and no environment, because it
        // administers all of them.
        renderPage();

        const row = (await screen.findByText("FAM administrator")).closest(
            "tr"
        ) as HTMLTableRowElement;
        // By column, not by "a dash somewhere in the row": this row now carries
        // two, since a FAM administrator is delegated no single role either.
        // Application, Environment, Role, May grant.
        expect(row.cells[1]).toHaveTextContent("—");
        // And the one that is scoped to an environment still says so.
        const scoped = screen.getByText("Application administrator").closest(
            "tr"
        ) as HTMLElement;
        expect(within(scoped).getByText("DEV")).toBeInTheDocument();
    });

    it("tells two delegations in one application apart", async () => {
        /*
            The reported symptom: somebody delegated two roles in one
            application holds two roles and so gets two rows, and both read
            "Sandbox REPT / TEST / Delegated administrator" - the same sentence
            twice, with nothing saying why it appeared at all, let alone twice.
        */
        fetchSelfPermissions.mockResolvedValue([
            {
                role: "DELEGATED_ADMIN",
                role_description: "Delegated administrator",
                application_name: "Sandbox REPT",
                environment: "test",
                delegated_role_name: "REPT_ADMIN",
                delegated_role_display_name: "Administrator",
                scopes: [],
            },
            {
                role: "DELEGATED_ADMIN",
                role_description: "Delegated administrator",
                application_name: "Sandbox REPT",
                environment: "test",
                // No sidecar, so the code is what there is to show.
                delegated_role_name: "REPT_VIEWER",
                delegated_role_display_name: null,
                scopes: [],
            },
        ]);
        renderPage();

        expect(await screen.findByText("Administrator")).toBeInTheDocument();
        expect(screen.getByText("REPT_VIEWER")).toBeInTheDocument();
    });

    it("says what a scoped delegation is narrowed to", async () => {
        // Two delegations of one role for different districts are two rows that
        // would otherwise read alike - the same duplication one level down.
        fetchSelfPermissions.mockResolvedValue([
            {
                role: "DELEGATED_ADMIN",
                role_description: "Delegated administrator",
                application_name: "FREP",
                environment: "dev",
                delegated_role_name: "CHR_FREP_EDITOR",
                delegated_role_display_name: "Editor",
                scopes: [{ type: "DISTRICT", value: "DCC", label: "Cariboo" }],
            },
        ]);
        renderPage();

        expect(await screen.findByText("Editor (Cariboo)")).toBeInTheDocument();
    });

    it("renders the fast half without waiting for the slow one", async () => {
        // Fetching them together would make the administrative list wait on a
        // CSS fan-out that takes seconds.
        fetchSelfApplicationRoles.mockReturnValue(new Promise(() => {}));
        renderPage();

        // The administrative half has real rows...
        expect(await screen.findByText("FAM administrator")).toBeInTheDocument();

        // ...while the slow half shows a skeleton in place of its table, rather
        // than an empty state that would read as "you hold no roles".
        expect(
            sectionFor("Application roles").querySelector(".cds--skeleton")
        ).not.toBeNull();
        expect(
            within(sectionFor("Application roles")).queryByText(
                "You hold no roles in any application"
            )
        ).not.toBeInTheDocument();
        expect(
            sectionFor("Administrative permissions").querySelector(".cds--skeleton")
        ).toBeNull();
    });

    it("says so plainly when there is nothing to show", async () => {
        // An empty table with no explanation reads as a screen that failed.
        fetchSelfPermissions.mockResolvedValue([]);
        fetchSelfApplicationRoles.mockResolvedValue([]);
        renderPage();

        expect(
            await screen.findByText("You hold no roles in any application")
        ).toBeInTheDocument();
        expect(
            screen.getByText("You do not administer any applications")
        ).toBeInTheDocument();
    });

    it("reports each half's failure against that half", async () => {
        fetchSelfApplicationRoles.mockRejectedValue(new Error("CSS is down"));
        renderPage();

        expect(
            await screen.findByText("Your application roles could not be loaded")
        ).toBeInTheDocument();
        // The other half loaded, so it is not reported as broken.
        expect(
            screen.queryByText("Your permissions could not be loaded")
        ).not.toBeInTheDocument();
        expect(screen.getByText("FAM administrator")).toBeInTheDocument();
    });
});
