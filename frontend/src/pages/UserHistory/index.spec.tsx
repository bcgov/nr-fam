import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { NotificationProvider } from "@/context/notification/NotificationProvider";

/**
 * What has happened to people's access in one application.
 *
 * <p>Three steps that wait on each other: an application, then the people
 * something has happened to in it, then one person's trail. What is worth
 * pinning is that each stays shut until the one above is answered, that the
 * people come from the trail rather than from CSS, and that changing the
 * application cannot leave one application's name over another's history.
 */

const getCssApplications = vi.fn();
const getPermissionAuditUsersByApplication = vi.fn();
const getPermissionAuditHistoryByUserAndApplication = vi.fn();

vi.mock("@/services/ApiServiceFactory", () => ({
    AppActlApiService: {
        permissionAuditApi: {
            getPermissionAuditUsersByApplication: (
                integrationId: number,
                environment: string
            ) => getPermissionAuditUsersByApplication(integrationId, environment),
            getPermissionAuditHistoryByUserAndApplication: (
                guid: string,
                userType: string,
                integrationId: number,
                environment: string
            ) =>
                getPermissionAuditHistoryByUserAndApplication(
                    guid,
                    userType,
                    integrationId,
                    environment
                ),
        },
    },
    AdminMgmtApiService: {
        cssIntegrationsApi: {
            getCssApplications: () => getCssApplications(),
            getCssApplicationRoles: () => Promise.resolve({ data: [] }),
        },
    },
}));

const { UserHistory } = await import("./index");

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

const JANE = {
    target_user_guid: "AAAA1111",
    target_user_type: "IDIR",
    username: "JSMITH",
    first_name: "Jane",
    last_name: "Smith",
    email: "jane.smith@gov.bc.ca",
    last_change_date: "2026-08-02T00:00:00Z",
};

const BOB = {
    target_user_guid: "BBBB2222",
    target_user_type: "IDIR",
    username: "BLEE",
    first_name: "Bob",
    last_name: "Lee",
    email: "bob.lee@gov.bc.ca",
    last_change_date: "2026-08-01T00:00:00Z",
};

const renderPage = () => {
    const queryClient = new QueryClient({
        defaultOptions: { queries: { retry: false } },
    });
    return render(
        <QueryClientProvider client={queryClient}>
            <MemoryRouter initialEntries={["/user-history"]}>
                <NotificationProvider>
                    <Routes>
                        <Route path="/user-history" element={<UserHistory />} />
                    </Routes>
                </NotificationProvider>
            </MemoryRouter>
        </QueryClientProvider>
    );
};

const chooseApplication = async (description = "FREP (DEV)") => {
    await userEvent.click(
        await screen.findByRole("combobox", { name: /Application/ })
    );
    await userEvent.click(await screen.findByText(description));
};

describe("UserHistory", () => {
    beforeEach(() => {
        getCssApplications.mockReset().mockResolvedValue({ data: [FREP, FAM] });
        getPermissionAuditUsersByApplication
            .mockReset()
            .mockResolvedValue({ data: [JANE, BOB] });
        getPermissionAuditHistoryByUserAndApplication
            .mockReset()
            .mockResolvedValue({ data: [] });
    });

    it("opens on the application, with nothing below it", async () => {
        // A user list before an application is chosen would be a list of
        // nobody, and a history table would have nothing to be a history of.
        renderPage();

        expect(
            await screen.findByRole("heading", { name: "Choose an application" })
        ).toBeInTheDocument();
        expect(
            screen.queryByRole("heading", { name: "Choose a user" })
        ).not.toBeInTheDocument();
        expect(
            screen.queryByRole("heading", { name: "History" })
        ).not.toBeInTheDocument();
    });

    it("keeps the picker named without labelling it twice", async () => {
        /*
            The step is called "Choose an application" and the placeholder says
            the same, so a visible "Application" over the box was a third. The
            name still has to reach a screen reader, though - Carbon applies
            aria-label only when titleText is absent, so dropping one without
            adding the other leaves an unnamed combobox.
        */
        renderPage();

        const picker = await screen.findByRole("combobox", { name: "Application" });
        expect(picker).toBeInTheDocument();
        // The visible label is gone - the placeholder is what is on screen.
        expect(screen.queryByText("Application")).not.toBeInTheDocument();
    });

    it("offers no user search", async () => {
        // The people come from the trail now, not from the directory: searching
        // IDIR would offer people nothing has ever happened to here.
        renderPage();

        expect(
            screen.queryByRole("button", { name: "Search users" })
        ).not.toBeInTheDocument();
    });

    it("leaves FAM's own application out", async () => {
        renderPage();
        await userEvent.click(
            await screen.findByRole("combobox", { name: /Application/ })
        );

        expect(await screen.findByText("FREP (DEV)")).toBeInTheDocument();
        expect(
            screen.queryByText("Forests Access Management (DEV)")
        ).not.toBeInTheDocument();
    });

    it("lists the people the trail knows about, once an application is chosen", async () => {
        renderPage();
        await chooseApplication();

        await waitFor(() =>
            expect(getPermissionAuditUsersByApplication).toHaveBeenCalledWith(
                6538,
                "dev"
            )
        );
        expect(await screen.findByText("Jane Smith")).toBeInTheDocument();
        expect(screen.getByText("Bob Lee")).toBeInTheDocument();
    });

    it("shows the history only once a user is chosen", async () => {
        renderPage();
        await chooseApplication();
        await screen.findByText("Jane Smith");

        expect(
            screen.queryByRole("heading", { name: "History" })
        ).not.toBeInTheDocument();

        await userEvent.click(screen.getByText("Jane Smith"));

        expect(
            await screen.findByRole("heading", { name: "History" })
        ).toBeInTheDocument();
        await waitFor(() =>
            expect(
                getPermissionAuditHistoryByUserAndApplication
            ).toHaveBeenCalledWith("AAAA1111", "IDIR", 6538, "dev")
        );
    });

    describe("choosing a user", () => {
        it("offers a radio per row and checks the chosen one", async () => {
            // One choice at a time, so a radio - and it has to show which.
            renderPage();
            await chooseApplication();
            await screen.findByText("Jane Smith");

            const radios = screen.getAllByRole("radio");
            expect(radios).toHaveLength(2);
            expect(radios.every((radio) => !(radio as HTMLInputElement).checked)).toBe(
                true
            );

            await userEvent.click(
                screen.getByRole("radio", { name: /Show the history of JSMITH/ })
            );

            expect(
                (screen.getByRole("radio", {
                    name: /Show the history of JSMITH/,
                }) as HTMLInputElement).checked
            ).toBe(true);
            expect(
                (screen.getByRole("radio", {
                    name: /Show the history of BLEE/,
                }) as HTMLInputElement).checked
            ).toBe(false);
        });

        it("names each radio, since the column has no heading", async () => {
            // An unlabelled control in a column with no heading is two rows of
            // "radio button" to a screen reader.
            renderPage();
            await chooseApplication();
            await screen.findByText("Jane Smith");

            expect(
                screen.getByRole("radio", { name: "Show the history of JSMITH" })
            ).toBeInTheDocument();
        });

        it("selecting the radio shows that person's history", async () => {
            renderPage();
            await chooseApplication();
            await screen.findByText("Jane Smith");

            await userEvent.click(
                screen.getByRole("radio", { name: /Show the history of BLEE/ })
            );

            await waitFor(() =>
                expect(
                    getPermissionAuditHistoryByUserAndApplication
                ).toHaveBeenCalledWith("BBBB2222", "IDIR", 6538, "dev")
            );
        });

        it("keeps the pagination inside the table's own frame", async () => {
            /*
                That frame clips its overflow, and the dividers in the bar are
                drawn taller than the controls they sit beside - see
                styles/_tables.scss. Outside it, each one runs past the bar.
            */
            renderPage();
            await chooseApplication();
            await screen.findByText("Jane Smith");

            const pagination = document.querySelector(".cds--pagination");
            expect(pagination?.closest(".fam-table")).not.toBeNull();
        });
    });

    it("names the person in the history subtitle as they read, not as they are keyed", async () => {
        // The username identifies them; the name is what somebody recognises.
        renderPage();
        await chooseApplication();
        await userEvent.click(await screen.findByText("Jane Smith"));

        expect(
            await screen.findByText(
                "Every recorded change to Jane Smith's access in FREP (DEV)"
            )
        ).toBeInTheDocument();
    });

    it("falls back to the username when the trail recorded no name", async () => {
        // A row whose snapshot would not read still has a history worth showing.
        getPermissionAuditUsersByApplication.mockResolvedValue({
            data: [{ ...JANE, first_name: null, last_name: null }],
        });

        renderPage();
        await chooseApplication();
        await userEvent.click(await screen.findByText("JSMITH"));

        expect(
            await screen.findByText(
                "Every recorded change to JSMITH's access in FREP (DEV)"
            )
        ).toBeInTheDocument();
    });

    it("forgets the chosen user when the application changes", async () => {
        /*
            The person belongs to the previous application's trail. Keeping them
            selected would put one application's name over another's history,
            which is the one thing this screen must never do.
        */
        getCssApplications.mockResolvedValue({
            data: [FREP, { ...FREP, environment: "test", description: "FREP (TEST)" }],
        });

        renderPage();
        await chooseApplication();
        await userEvent.click(await screen.findByText("Jane Smith"));
        await screen.findByRole("heading", { name: "History" });

        await chooseApplication("FREP (TEST)");

        await waitFor(() =>
            expect(
                screen.queryByRole("heading", { name: "History" })
            ).not.toBeInTheDocument()
        );
        await waitFor(() =>
            expect(getPermissionAuditUsersByApplication).toHaveBeenLastCalledWith(
                6538,
                "test"
            )
        );
    });

    it("says so plainly when nothing has been recorded", async () => {
        // Different from "nobody has access": the trail simply has nothing.
        getPermissionAuditUsersByApplication.mockResolvedValue({ data: [] });

        renderPage();
        await chooseApplication();

        expect(
            await screen.findByText(/Nothing has been recorded against/)
        ).toBeInTheDocument();
    });

    it("says the history could not be read rather than that there is none", async () => {
        getPermissionAuditUsersByApplication.mockRejectedValue({
            response: { data: { detail: { description: "Not yours to read." } } },
        });

        renderPage();
        await chooseApplication();

        expect(await screen.findByText("Not yours to read.")).toBeInTheDocument();
    });

    it("paginates the people rather than showing every one", async () => {
        getPermissionAuditUsersByApplication.mockResolvedValue({
            data: Array.from({ length: 12 }, (_, index) => ({
                ...JANE,
                target_user_guid: `GUID${index}`,
                username: `USER${index}`,
                first_name: "User",
                last_name: String(index),
            })),
        });

        renderPage();
        await chooseApplication();
        await screen.findByText("User 0");

        const pagination = document.querySelector(".cds--pagination");
        expect(pagination).not.toBeNull();
        expect(within(pagination as HTMLElement).getByText(/12 items/)).toBeInTheDocument();
    });
});
