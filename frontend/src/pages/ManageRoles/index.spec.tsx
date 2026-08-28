import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

/**
 * Defining the roles an application offers.
 *
 * Two things distinguish this screen from the rest of FAM: it changes what roles
 * exist rather than who holds them, and deleting one takes the access with it.
 * Both are what the tests are about.
 */

const getCssApplicationsForRoleManagement = vi.fn();
const getCssApplicationRoles = vi.fn();
const getCssApplicationRoleMemberCounts = vi.fn();
const createCssApplicationRole = vi.fn();
const createCssApplicationRoleAllEnvironments = vi.fn();
const deleteCssApplicationRole = vi.fn();

vi.mock("@/services/ApiServiceFactory", () => ({
    AdminMgmtApiService: {
        cssIntegrationsApi: {
            getCssApplicationsForRoleManagement: () =>
                getCssApplicationsForRoleManagement(),
            getCssApplicationRoles: () => getCssApplicationRoles(),
            getCssApplicationRoleMemberCounts: () =>
                getCssApplicationRoleMemberCounts(),
            createCssApplicationRole: (
                integrationId: number,
                environment: string,
                request: unknown
            ) => createCssApplicationRole(integrationId, environment, request),
            // Rest args, deliberately: a fixed two-parameter signature here
            // would silently drop a third the code passed, and the assertion
            // that this call names no environment would be testing the mock.
            createCssApplicationRoleAllEnvironments: (...args: unknown[]) =>
                createCssApplicationRoleAllEnvironments(...args),
            deleteCssApplicationRole: (
                integrationId: number,
                environment: string,
                roleName: string
            ) => deleteCssApplicationRole(integrationId, environment, roleName),
        },
    },
    AppActlApiService: {},
}));


const { ManageRoles } = await import("./index");
const { NotificationProvider } = await import(
    "@/context/notification/NotificationProvider"
);

/*
    As get_css_applications_for_role_management returns them: already narrowed to
    what the caller may define roles for, with every_environment answering
    whether they hold all of the integration's environments.
*/
const FREP = {
    integration_id: 6538,
    environment: "dev",
    description: "FREP (DEV)",
    every_environment: true,
};

const EDITOR = {
    name: "FREP_EDITOR",
    display_name: "Editor",
    description: "Edit within a district",
    role_type_district: true,
    role_type_client: false,
};

const COMPOUND = {
    name: "FREP_BOTH",
    display_name: "Both",
    role_type_district: true,
    role_type_client: true,
};

const CONFIRM_DELETE = /Delete$/;

const renderPage = () => {
    const queryClient = new QueryClient({
        defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    });
    render(
        <QueryClientProvider client={queryClient}>
            <NotificationProvider>
                <ManageRoles />
            </NotificationProvider>
        </QueryClientProvider>
    );
};

const chooseApplication = async (description = "FREP (DEV)") => {
    await userEvent.click(screen.getByRole("combobox", { name: /application/i }));
    await userEvent.click(await screen.findByText(description));
    await waitFor(() =>
        expect(
            screen.getByRole("heading", { name: "Create a role" })
        ).toBeInTheDocument()
    );
};

const fillForm = async (code: string, name: string, description = "") => {
    await userEvent.type(screen.getByLabelText("Role code"), code);
    await userEvent.type(screen.getByLabelText("Role name"), name);
    if (description) {
        await userEvent.type(screen.getByLabelText("Description (Optional)"), description);
    }
};

describe("ManageRoles", () => {
    beforeEach(() => {
        // Already filtered and FAM-excluded by the backend, which is what this
        // screen's own list does - see get_css_applications_for_role_management.
        getCssApplicationsForRoleManagement
            .mockReset()
            .mockResolvedValue({ data: [FREP] });
        getCssApplicationRoles.mockReset().mockResolvedValue({ data: [EDITOR] });
        getCssApplicationRoleMemberCounts
            .mockReset()
            .mockResolvedValue({ data: [{ role_name: "FREP_EDITOR", member_count: 3 }] });
        createCssApplicationRole
            .mockReset()
            .mockResolvedValue({ data: { name: "FREP_NEW", display_name: "New" } });
        createCssApplicationRoleAllEnvironments.mockReset().mockResolvedValue({
            data: {
                role_code: "FREP_NEW",
                description: "New",
                environments: ["dev", "test", "prod"],
            },
        });
        deleteCssApplicationRole
            .mockReset()
            .mockResolvedValue({ data: { role_name: "FREP_EDITOR" } });
    });

    it("shows nothing but the picker until an application is chosen", async () => {
        // The picker's own placeholder says what to do, so a sentence beneath it
        // saying the same thing was only repetition.
        renderPage();

        expect(
            await screen.findByRole("combobox", { name: /application/i })
        ).toBeInTheDocument();
        expect(
            screen.queryByRole("heading", { name: "Create a role" })
        ).not.toBeInTheDocument();
        expect(
            screen.queryByRole("heading", { name: "Existing roles" })
        ).not.toBeInTheDocument();
        expect(screen.queryByRole("table")).not.toBeInTheDocument();
    });

    it("says nothing is wrong until an action is taken", async () => {
        // The errors were seeded by validating a blank form, so "A role code is
        // required" appeared the moment an application was chosen - telling
        // somebody off for not having filled in a form they had just been shown.
        renderPage();
        await chooseApplication();

        expect(
            screen.queryByText("A role code is required")
        ).not.toBeInTheDocument();
        expect(
            screen.queryByText("A role name is required")
        ).not.toBeInTheDocument();

        await userEvent.click(screen.getByRole("button", { name: "Create role" }));

        expect(
            await screen.findByText("A role code is required")
        ).toBeInTheDocument();
    });

    it("says nothing is wrong until an action is taken, for either button", async () => {
        renderPage();
        await chooseApplication();

        await userEvent.click(
            screen.getByRole("button", { name: "Create in all environments" })
        );

        expect(
            await screen.findByText("A role code is required")
        ).toBeInTheDocument();
        expect(createCssApplicationRoleAllEnvironments).not.toHaveBeenCalled();
    });

    it("creates a role in the chosen environment", async () => {
        renderPage();
        await chooseApplication();
        await fillForm("FREP_NEW", "New", "Does a thing");

        await userEvent.click(screen.getByRole("button", { name: "Create role" }));

        await waitFor(() => expect(createCssApplicationRole).toHaveBeenCalled());
        const [integrationId, environment, request] =
            createCssApplicationRole.mock.calls[0];
        expect(integrationId).toBe(6538);
        expect(environment).toBe("dev");
        expect(request).toEqual({
            role_code: "FREP_NEW",
            role_name: "New",
            description: "Does a thing",
            requires_district: false,
            requires_region: false,
            requires_forest_client: false,
        });
    });

    it("upper cases the code on the way out", async () => {
        // Accepted as typed rather than rejected on a technicality.
        renderPage();
        await chooseApplication();
        await fillForm("frep_new", "New");

        await userEvent.click(screen.getByRole("button", { name: "Create role" }));

        await waitFor(() => expect(createCssApplicationRole).toHaveBeenCalled());
        expect(createCssApplicationRole.mock.calls[0][2]).toMatchObject({
            role_code: "FREP_NEW",
        });
    });

    it("carries both scope flags for a compound role", async () => {
        // A role scoped by district AND forest client is granted per pair; one
        // flag alone would misdescribe what a grant will ask for.
        renderPage();
        await chooseApplication();
        await fillForm("FREP_NEW", "New");
        await userEvent.click(
            screen.getByLabelText("Requires a district selection")
        );
        await userEvent.click(
            screen.getByLabelText("Requires a forest client selection")
        );

        await userEvent.click(screen.getByRole("button", { name: "Create role" }));

        await waitFor(() => expect(createCssApplicationRole).toHaveBeenCalled());
        expect(createCssApplicationRole.mock.calls[0][2]).toMatchObject({
            requires_district: true,
            requires_forest_client: true,
        });
    });

    it("carries the region flag for a region-scoped role", async () => {
        // Regions are their own dimension: a role may be scoped by region, by
        // district, or by both, and the backend composes a marker per dimension.
        renderPage();
        await chooseApplication();
        await fillForm("FREP_NEW", "New");
        await userEvent.click(
            screen.getByLabelText("Requires a region selection")
        );

        await userEvent.click(screen.getByRole("button", { name: "Create role" }));

        await waitFor(() => expect(createCssApplicationRole).toHaveBeenCalled());
        expect(createCssApplicationRole.mock.calls[0][2]).toMatchObject({
            requires_region: true,
            requires_district: false,
            requires_forest_client: false,
        });
    });

    it("carries every scope flag for a role scoped three ways", async () => {
        renderPage();
        await chooseApplication();
        await fillForm("FREP_NEW", "New");
        await userEvent.click(
            screen.getByLabelText("Requires a district selection")
        );
        await userEvent.click(
            screen.getByLabelText("Requires a region selection")
        );
        await userEvent.click(
            screen.getByLabelText("Requires a forest client selection")
        );

        await userEvent.click(screen.getByRole("button", { name: "Create role" }));

        await waitFor(() => expect(createCssApplicationRole).toHaveBeenCalled());
        expect(createCssApplicationRole.mock.calls[0][2]).toMatchObject({
            requires_district: true,
            requires_region: true,
            requires_forest_client: true,
        });
    });

    it("sends no environment when creating everywhere", async () => {
        // The endpoint uses the integration's own environment list, so an
        // application with only dev and test gets two rather than a request for
        // a prod that does not exist.
        renderPage();
        await chooseApplication();
        await fillForm("FREP_NEW", "New");

        await userEvent.click(
            screen.getByRole("button", { name: "Create in all environments" })
        );

        await waitFor(() =>
            expect(createCssApplicationRoleAllEnvironments).toHaveBeenCalled()
        );
        expect(createCssApplicationRoleAllEnvironments.mock.calls[0]).toHaveLength(2);
        // Named twice on purpose now: the line below the form, and the toast.
        expect(
            (await screen.findAllByText(/dev, test, prod/)).length
        ).toBeGreaterThan(0);
    });

    it("refuses a malformed code without sending it", async () => {
        renderPage();
        await chooseApplication();
        await fillForm("FREP-NEW", "New");

        await userEvent.click(screen.getByRole("button", { name: "Create role" }));

        expect(
            await screen.findByText(/Use letters, digits and underscores only/)
        ).toBeInTheDocument();
        expect(createCssApplicationRole).not.toHaveBeenCalled();
    });

    it("reports the backend's reason when a code is taken", async () => {
        createCssApplicationRole.mockRejectedValue({
            response: { data: { detail: { description: "FREP_NEW already exists in dev." } } },
        });
        renderPage();
        await chooseApplication();
        await fillForm("FREP_NEW", "New");

        await userEvent.click(screen.getByRole("button", { name: "Create role" }));

        expect(
            await screen.findByText("FREP_NEW already exists in dev.")
        ).toBeInTheDocument();
    });

    it("lists existing roles with their scope and member count", async () => {
        getCssApplicationRoles.mockResolvedValue({ data: [EDITOR, COMPOUND] });
        renderPage();
        await chooseApplication();

        const row = (await screen.findByText("FREP_EDITOR")).closest(
            "tr"
        ) as HTMLElement;
        expect(within(row).getByText("District")).toBeInTheDocument();
        expect(within(row).getByText("3")).toBeInTheDocument();

        // Both chips for a compound role.
        const compound = screen.getByText("FREP_BOTH").closest("tr") as HTMLElement;
        expect(within(compound).getByText("District")).toBeInTheDocument();
        expect(within(compound).getByText("Forest client")).toBeInTheDocument();
    });

    it("shows a dash rather than 0 while the counts are loading", async () => {
        // An unknown must never read as "nobody" - it is the number somebody
        // decides a deletion on.
        getCssApplicationRoleMemberCounts.mockReturnValue(new Promise(() => {}));
        renderPage();
        await chooseApplication();

        const row = (await screen.findByText("FREP_EDITOR")).closest(
            "tr"
        ) as HTMLElement;
        expect(within(row).getByText("—")).toBeInTheDocument();
        expect(within(row).queryByText("0")).not.toBeInTheDocument();
    });

    it("shows 0 once the counts arrive and the role has nobody", async () => {
        // The backend omits roles with no members, so an absent entry after a
        // successful load genuinely means none.
        getCssApplicationRoleMemberCounts.mockResolvedValue({ data: [] });
        renderPage();
        await chooseApplication();

        const row = (await screen.findByText("FREP_EDITOR")).closest(
            "tr"
        ) as HTMLElement;
        await waitFor(() => expect(within(row).getByText("0")).toBeInTheDocument());
    });

    it("says how many people lose access before deleting", async () => {
        renderPage();
        await chooseApplication();
        await userEvent.click(
            await screen.findByRole("button", { name: "Remove FREP_EDITOR" })
        );

        const message = await screen.findByText(/Are you sure you want to delete/);
        expect(message.textContent).toContain("3 people hold");
        expect(message.textContent).toContain("This cannot be undone.");
        expect(deleteCssApplicationRole).not.toHaveBeenCalled();
    });

    it("says nobody holds it when nobody does", async () => {
        getCssApplicationRoleMemberCounts.mockResolvedValue({ data: [] });
        renderPage();
        await chooseApplication();
        await waitFor(() =>
            expect(getCssApplicationRoleMemberCounts).toHaveBeenCalled()
        );
        await userEvent.click(
            screen.getByRole("button", { name: "Remove FREP_EDITOR" })
        );

        const message = await screen.findByText(/Are you sure you want to delete/);
        expect(message.textContent).toContain("Nobody currently holds it.");
    });

    it("deletes the role and says so", async () => {
        renderPage();
        await chooseApplication();
        await userEvent.click(
            await screen.findByRole("button", { name: "Remove FREP_EDITOR" })
        );
        await userEvent.click(
            await screen.findByRole("button", { name: CONFIRM_DELETE })
        );

        await waitFor(() =>
            expect(deleteCssApplicationRole).toHaveBeenCalledWith(
                6538,
                "dev",
                "FREP_EDITOR"
            )
        );
        const toast = await screen.findByRole("status");
        expect(within(toast).getByText("Role deleted")).toBeInTheDocument();
        // Names the role and the application and stops - the derived roles and
        // lost access are consequences, not separate outcomes, and counting them
        // made a routine deletion read like an incident report.
        expect(toast.textContent).toContain(
            "Role FREP_EDITOR was deleted from FREP (DEV)."
        );
        expect(toast.textContent).not.toMatch(/derived/i);
    });

    it("says out loud that the role was created", async () => {
        /*
            The line under the form already names the new role, but the form
            empties itself on success - and a cleared form is exactly what an
            unsaved one looks like. Creating a role is announced the same way
            granting a permission is.
        */
        renderPage();
        await chooseApplication();
        await fillForm("FREP_NEW", "New role");

        await userEvent.click(screen.getByRole("button", { name: "Create role" }));

        expect(await screen.findByText("Role created")).toBeInTheDocument();
    });

    describe("who the picker offers", () => {
        /*
            The screen shows what the endpoint returns. That list is narrowed
            server-side to the applications this caller may define roles for -
            the ordinary application list is filtered by who may manage access,
            which is empty for a DevOps administrator, and this picker was
            therefore empty for exactly the people the screen was opened up to.
        */
        it("shows what its own endpoint returned, unfiltered", async () => {
            getCssApplicationsForRoleManagement.mockResolvedValue({
                data: [FREP, { ...FREP, environment: "test", description: "FREP (TEST)" }],
            });

            renderPage();
            await waitFor(() =>
                expect(getCssApplicationsForRoleManagement).toHaveBeenCalled()
            );

            await userEvent.click(
                screen.getByRole("combobox", { name: /application/i })
            );
            expect(await screen.findByText("FREP (DEV)")).toBeInTheDocument();
            expect(screen.getByText("FREP (TEST)")).toBeInTheDocument();
        });

        it("asks the role-management list, not the access one", async () => {
            // The access list is empty for a DevOps administrator; asking it is
            // what left this picker blank.
            renderPage();

            await waitFor(() =>
                expect(getCssApplicationsForRoleManagement).toHaveBeenCalled()
            );
        });

        it("offers the all-environments button only when the row says so", async () => {
            /*
                That call writes to every environment, so it takes authority over
                every one. Only the backend can answer it: this list carries only
                the environments the caller may manage, so holding DEV alone
                would otherwise look like holding the whole integration.
            */
            getCssApplicationsForRoleManagement.mockResolvedValue({
                data: [{ ...FREP, every_environment: false }],
            });

            renderPage();
            await waitFor(() =>
                expect(getCssApplicationsForRoleManagement).toHaveBeenCalled()
            );
            await userEvent.click(
                screen.getByRole("combobox", { name: /application/i })
            );
            await userEvent.click(await screen.findByText("FREP (DEV)"));

            expect(
                await screen.findByRole("button", { name: "Create role" })
            ).toBeInTheDocument();
            expect(
                screen.queryByRole("button", { name: /all environments/i })
            ).not.toBeInTheDocument();
        });

        it("offers it when the row says they hold every environment", async () => {
            renderPage();
            await waitFor(() =>
                expect(getCssApplicationsForRoleManagement).toHaveBeenCalled()
            );
            await userEvent.click(
                screen.getByRole("combobox", { name: /application/i })
            );
            await userEvent.click(await screen.findByText("FREP (DEV)"));

            expect(
                await screen.findByRole("button", { name: /all environments/i })
            ).toBeInTheDocument();
        });
    });
});
