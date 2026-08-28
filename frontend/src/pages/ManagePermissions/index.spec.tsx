import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";

/**
 * Which tabs the permissions screen offers, per application.
 *
 * FAM administers itself through the same screen as everything else, but it is
 * not an ordinary application: the APP_ADMIN and DELEGATED_ADMIN roles on its
 * integration all record who administers some *other* application, because that
 * is the only place such a role can sit and still reach FAM's token. Tabs asking
 * "who are FAM's delegated admins" listed those people, none of whom is one.
 */

const getApplications = vi.fn();
const fetchSelfPermissions = vi.fn();

vi.mock("@/services/ApiServiceFactory", () => ({
    AdminMgmtApiService: {
        cssIntegrationsApi: {
            getCssApplications: () => getApplications(),
            getCssUserRoleAssignments: () => Promise.resolve({ data: [] }),
            getCssApplicationAdministrators: () => Promise.resolve({ data: [] }),
        },
    },
    AppActlApiService: {},
}));

vi.mock("@/services/AuthApiService", () => ({
    fetchSelfPermissions: () => fetchSelfPermissions(),
}));

const { ManagePermissions } = await import("./index");
const { SelectedAppProvider } = await import(
    "@/context/application/SelectedAppProvider"
);
const { NotificationProvider } = await import(
    "@/context/notification/NotificationProvider"
);

const FREP = {
    integration_id: 6538,
    environment: "dev",
    name: "FREP",
    description: "FREP (DEV)",
    fam_application: false,
};

const FAM = {
    integration_id: 12345,
    environment: "dev",
    name: "FAM",
    description: "Forests Access Management (DEV)",
    fam_application: true,
};

const renderPage = (seed?: (client: QueryClient) => void) => {
    const queryClient = new QueryClient({
        defaultOptions: { queries: { retry: false } },
    });
    seed?.(queryClient);
    return render(
        <QueryClientProvider client={queryClient}>
            <MemoryRouter>
                <NotificationProvider>
                    <SelectedAppProvider>
                        <ManagePermissions />
                    </SelectedAppProvider>
                </NotificationProvider>
            </MemoryRouter>
        </QueryClientProvider>
    );
};

/** Chooses an application through the picker, as a person would. */
const choose = async (description: string) => {
    const combo = screen.getByRole("combobox", { name: /application/i });
    await userEvent.click(combo);
    await userEvent.click(await screen.findByText(description));
};

const tabNames = () =>
    screen.queryAllByRole("tab").map((tab) => tab.textContent ?? "");

describe("ManagePermissions", () => {
    beforeEach(() => {
        getApplications.mockReset().mockResolvedValue({ data: [FREP, FAM] });
        // A FAM administrator, who may see the admin tabs on any application.
        fetchSelfPermissions.mockReset().mockResolvedValue([{ role: "FAM_ADMIN" }]);
    });

    it("shows the empty state until an application is chosen", async () => {
        renderPage();

        expect(await screen.findByText("Nothing to show yet!")).toBeInTheDocument();
        expect(screen.queryAllByRole("tab")).toHaveLength(0);
    });

    it("keeps Add permission on the Users tab, not beside the picker", async () => {
        // It sat next to the application picker, which put a grant action above
        // the tab strip while the two administrator tabs carried their own add
        // buttons inside their panels. All three are in the same place now.
        renderPage();
        await waitFor(() => expect(getApplications).toHaveBeenCalled());

        expect(
            screen.queryByRole("button", { name: /Add permission/ })
        ).not.toBeInTheDocument();

        await choose("FREP (DEV)");

        const panel = await screen.findByRole("tabpanel");
        expect(
            within(panel).getByRole("button", { name: /Add permission/ })
        ).toBeInTheDocument();
        expect(
            within(panel).getByRole("button", { name: /Bulk upload/ })
        ).toBeInTheDocument();
    });

    it("offers the admin tabs for an ordinary application", async () => {
        renderPage();
        await waitFor(() => expect(getApplications).toHaveBeenCalled());

        await choose("FREP (DEV)");

        await waitFor(() =>
            expect(tabNames().join(" ")).toContain("Delegated admins")
        );
        expect(tabNames().join(" ")).toContain("Application admins");
    });

    it("offers only Users for FAM itself", async () => {
        renderPage();
        await waitFor(() => expect(getApplications).toHaveBeenCalled());

        await choose("Forests Access Management (DEV)");

        // Not a permissions question - a FAM admin still cannot see them here,
        // because there is nothing there to see.
        await waitFor(() => expect(tabNames().join(" ")).toContain("Users"));
        expect(tabNames().join(" ")).not.toContain("Delegated admins");
        expect(tabNames().join(" ")).not.toContain("Application admins");
    });

    it("mounts no administrators table for FAM", async () => {
        renderPage();
        await waitFor(() => expect(getApplications).toHaveBeenCalled());

        await choose("Forests Access Management (DEV)");
        await waitFor(() => expect(tabNames()).toHaveLength(1));

        // The tab strip is only half of it: a panel left mounted would still
        // fetch the roster and still show other applications' administrators.
        expect(
            vi.mocked(getApplications).mock.calls.length
        ).toBeGreaterThan(0);
        await waitFor(() =>
            expect(screen.queryByText("May grant")).not.toBeInTheDocument()
        );
    });

    it("falls back to Users when the open tab disappears", async () => {
        renderPage();
        await waitFor(() => expect(getApplications).toHaveBeenCalled());

        await choose("FREP (DEV)");
        await waitFor(() =>
            expect(tabNames().join(" ")).toContain("Delegated admins")
        );

        await userEvent.click(screen.getByRole("tab", { name: /Delegated admins/ }));
        await waitFor(() =>
            expect(
                screen.getByRole("tab", { name: /Delegated admins/ })
            ).toHaveAttribute("aria-selected", "true")
        );

        await choose("Forests Access Management (DEV)");

        // The tab that was open no longer exists. Left uncontrolled, Tabs keeps
        // its index and the panel below renders nothing at all - so what is
        // asserted is that SOMETHING is selected, and that it is Users.
        await waitFor(() => expect(tabNames()).toHaveLength(1));
        const usersTab = screen.getByRole("tab", { name: /Users/ });
        expect(usersTab).toHaveAttribute("aria-selected", "true");
        // And its panel is the one showing.
        const panel = screen.getByRole("tabpanel");
        expect(
            within(panel).getByText(/Forests Access Management \(DEV\) users/)
        ).toBeInTheDocument();
    });

    it("still hides the admin tabs from somebody who administers nothing", async () => {
        fetchSelfPermissions.mockResolvedValue([]);
        renderPage();
        await waitFor(() => expect(getApplications).toHaveBeenCalled());

        await choose("FREP (DEV)");

        // The FAM rule is an extra reason to hide them, not a replacement for
        // the permission check.
        await waitFor(() => expect(tabNames()).toHaveLength(1));
        expect(tabNames().join(" ")).not.toContain("Delegated admins");
    });

    it("shows the admin tabs to an application administrator of that application only", async () => {
        fetchSelfPermissions.mockResolvedValue([
            {
                role: "APP_ADMIN",
                css_integration_id: FREP.integration_id,
                environment: "dev",
            },
        ]);
        renderPage();
        await waitFor(() => expect(getApplications).toHaveBeenCalled());

        await choose("FREP (DEV)");

        await waitFor(() =>
            expect(tabNames().join(" ")).toContain("Application admins")
        );
    });

    it("hides the admin tabs from an administrator of a different application", async () => {
        fetchSelfPermissions.mockResolvedValue([
            {
                role: "APP_ADMIN",
                // Some other integration entirely.
                css_integration_id: 999,
                environment: "dev",
            },
        ]);
        renderPage();
        await waitFor(() => expect(getApplications).toHaveBeenCalled());

        await choose("FREP (DEV)");

        await waitFor(() => expect(tabNames()).toHaveLength(1));
        expect(tabNames().join(" ")).not.toContain("Application admins");
    });

    it("hides the admin tabs from an administrator of the same application in another environment", async () => {
        fetchSelfPermissions.mockResolvedValue([
            {
                role: "APP_ADMIN",
                css_integration_id: FREP.integration_id,
                // The same integration, but production. A test administrator is
                // not a production one.
                environment: "prod",
            },
        ]);
        renderPage();
        await waitFor(() => expect(getApplications).toHaveBeenCalled());

        await choose("FREP (DEV)");

        await waitFor(() => expect(tabNames()).toHaveLength(1));
        expect(tabNames().join(" ")).not.toContain("Application admins");
    });
});

/**
 * What a grant made on the previous screen has to say for itself here.
 *
 * These were banners at the top of the page - the last ones in the app. They are
 * toasts now, which for the failure list means the thing it was a banner *for*
 * has to survive the change: it names people somebody has to chase, so it waits
 * to be dismissed rather than expiring.
 */
describe("ManagePermissions grant outcome", () => {
    const outcome = (userId: string, error: string) => ({
        user: { userId, firstName: "Jane", lastName: "Smith" },
        role: { name: "FREP_EDITOR", display_name: "Editor" },
        results: [],
        error,
    });

    const seedGrant = (summary: unknown) => (client: QueryClient) =>
        client.setQueryData(["app-user-mutation-success"], summary);

    beforeEach(() => {
        getApplications.mockReset().mockResolvedValue({ data: [FREP, FAM] });
        fetchSelfPermissions.mockReset().mockResolvedValue([{ role: "FAM_ADMIN" }]);
    });

    it("names each user a grant did not reach, and why", async () => {
        renderPage(
            seedGrant({
                applicationName: "FREP (DEV)",
                outcomes: [outcome("JSMITH", "that user is at another organisation")],
            })
        );

        expect(
            await screen.findByText(/some permissions were not added in FREP \(DEV\)/i)
        ).toBeInTheDocument();
        // The reason, not just the count: it is the difference between a user
        // who cannot be granted and a directory that is down.
        expect(
            screen.getByText(/that user is at another organisation/)
        ).toBeInTheDocument();
        expect(screen.getByText(/Jane Smith \(JSMITH\)/)).toBeInTheDocument();
    });

    it("leaves the failure list up rather than expiring it", async () => {
        vi.useFakeTimers({ shouldAdvanceTime: true });
        try {
            renderPage(
                seedGrant({
                    applicationName: "FREP (DEV)",
                    outcomes: [outcome("JSMITH", "that user is at another organisation")],
                })
            );

            await screen.findByText(/some permissions were not added/i);
            await vi.advanceTimersByTimeAsync(60_000);

            // The one thing the banner did better, and the reason failures can
            // be toasts at all.
            expect(
                screen.getByText(/some permissions were not added/i)
            ).toBeInTheDocument();
        } finally {
            vi.useRealTimers();
        }
    });

    it("says nothing when no grant preceded the visit", async () => {
        renderPage();
        await waitFor(() => expect(getApplications).toHaveBeenCalled());

        expect(
            screen.queryByText(/were not added/i)
        ).not.toBeInTheDocument();
    });

    describe("the DevOps admins tab", () => {
        it("is offered to a FAM administrator", async () => {
            renderPage();
            await waitFor(() => expect(getApplications).toHaveBeenCalled());
            await choose("FREP (DEV)");

            await waitFor(() =>
                expect(tabNames().join(" ")).toContain("DevOps admins")
            );
        });

        it("is withheld from an application administrator", async () => {
            /*
                A DevOps admin decides what roles an application has. That is not
                authority an application administrator holds, so they cannot hand
                it out either - letting them would be a way to acquire it by
                proxy. They keep the other two tabs.
            */
            fetchSelfPermissions.mockResolvedValue([
                {
                    role: "APP_ADMIN",
                    css_integration_id: FREP.integration_id,
                    environment: "dev",
                },
            ]);

            renderPage();
            await waitFor(() => expect(getApplications).toHaveBeenCalled());
            await choose("FREP (DEV)");

            await waitFor(() =>
                expect(tabNames().join(" ")).toContain("Application admins")
            );
            expect(tabNames().join(" ")).not.toContain("DevOps admins");
        });

        it("is withheld on FAM's own application", async () => {
            // FAM's roles are its administrative tiers; they are not defined
            // from this screen, so there is nobody to list.
            renderPage();
            await waitFor(() => expect(getApplications).toHaveBeenCalled());
            await choose("Forests Access Management (DEV)");

            await waitFor(() =>
                expect(tabNames().join(" ")).not.toContain("DevOps admins")
            );
        });
    });
});
