import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it, vi } from "vitest";
import { AuthContext, type AuthContextValue } from "@/context/auth/AuthContext";
import type { AuthState } from "@/types/AuthTypes";
import { Layout } from "./index";

/**
 * The shell as a signed-in user meets it: header, profile panel, and the nav
 * showing only what their roles admit.
 *
 * Rendered whole rather than per-component because the thing worth asserting is
 * the wiring - roles arrive at the top and have to reach the nav three levels
 * down, and each of those hand-offs has been a place to drop them.
 */
const renderLayout = (state: Partial<AuthState>) => {
    const value: AuthContextValue = {
        authState: {
            isAuthenticated: true,
            famLoginUser: { displayName: "Jane Doe", username: "JDOE" },
            isAuthRestored: true,
            accessRoles: [],
            ...state,
        },
        login: async () => {},
        logout: vi.fn(async () => {}),
        ensureFreshToken: async () => {},
        forceRefreshSession: async () => {},
        acceptTermsOfUse: async () => {},
    };
    return render(
        <AuthContext.Provider value={value}>
            <MemoryRouter>
                <Layout accessRoles={value.authState.accessRoles}>
                    <p>page content</p>
                </Layout>
            </MemoryRouter>
        </AuthContext.Provider>
    );
};

describe("Layout", () => {
    it("renders the page inside the shell", () => {
        renderLayout({});

        expect(screen.getByText("page content")).toBeInTheDocument();
        expect(screen.getByTestId("bc-header__header")).toBeInTheDocument();
    });

    describe("the how-to guide", () => {
        const guideHref = () =>
            screen.queryByTestId("side-nav-link-how-to-guide")?.getAttribute("href");

        it("gives a delegated administrator the delegated administrator's guide", () => {
            renderLayout({
                accessRoles: ["DELEGATED_ADMIN_22264_DEV__FREP_EDITOR"],
            });

            expect(guideHref()).toBe("/fam-delegated-admin-guide.pdf");
        });

        it("gives an application administrator theirs", () => {
            renderLayout({ accessRoles: ["APP_ADMIN_22264_DEV"] });

            expect(guideHref()).toBe("/fam-app-admin-guide.pdf");
        });

        it("gives somebody who is both the application administrator's", () => {
            // It covers appointing administrators and everything the delegated
            // administrator's guide covers, so it is the one that answers more.
            renderLayout({
                accessRoles: [
                    "DELEGATED_ADMIN_22264_DEV__FREP_EDITOR",
                    "APP_ADMIN_6538_TEST",
                ],
            });

            expect(guideHref()).toBe("/fam-app-admin-guide.pdf");
        });

        it("gives a FAM administrator the application administrator's", () => {
            renderLayout({ accessRoles: ["FAM_ADMIN"] });

            expect(guideHref()).toBe("/fam-app-admin-guide.pdf");
        });

        it("offers none to a DevOps-only administrator", () => {
            // Neither guide is about defining roles.
            renderLayout({ accessRoles: ["DEVOPS_ADMIN_22264_DEV"] });

            expect(
                screen.queryByTestId("side-nav-link-how-to-guide")
            ).not.toBeInTheDocument();
        });

        it("opens in a new tab, above Report an issue", () => {
            renderLayout({ accessRoles: ["APP_ADMIN_22264_DEV"] });

            const guide = screen.getByTestId("side-nav-link-how-to-guide");
            expect(guide).toHaveAttribute("target", "_blank");
            expect(guide).toHaveAttribute("rel", expect.stringContaining("noopener"));

            // Reading order, not just presence: the guide comes first.
            const support = screen.getByTestId("side-nav-link-email-support");
            expect(
                guide.compareDocumentPosition(support) &
                    Node.DOCUMENT_POSITION_FOLLOWING
            ).toBeTruthy();
        });
    });

    it("offers Manage roles to a FAM administrator", () => {
        renderLayout({ accessRoles: ["FAM_ADMIN"] });

        expect(screen.getByTestId("side-nav-link-manage-roles")).toBeInTheDocument();
    });

    it("withholds Manage permissions from a DevOps-only administrator", () => {
        /*
            They manage no access, so the application picker on that screen is
            empty for them and every tab under it would be - a screen with
            nothing on it, offered by name. Manage roles is what they came for
            and stays.
        */
        renderLayout({ accessRoles: ["DEVOPS_ADMIN_6538_DEV"] });

        expect(
            screen.queryByTestId("side-nav-link-manage-permissions")
        ).not.toBeInTheDocument();
        expect(screen.getByTestId("side-nav-link-manage-roles")).toBeInTheDocument();
    });

    it("keeps Manage permissions for a DevOps admin who also administers access", () => {
        renderLayout({
            accessRoles: ["DEVOPS_ADMIN_6538_DEV", "APP_ADMIN_6538_DEV"],
        });

        expect(
            screen.getByTestId("side-nav-link-manage-permissions")
        ).toBeInTheDocument();
    });

    it("withholds Manage permissions from somebody who administers nothing", () => {
        /*
            It used to be offered to them, on the reasoning that an empty table
            answers "what do I administer" where an absent screen does not. What
            they actually met was an error under an application selector that
            could not be filled, which reads as a fault rather than an answer.
            They are now turned away at the shell - see RequireAnyFamRole - and
            never see this nav at all; the entry going with them is what keeps
            the two consistent.
        */
        renderLayout({ accessRoles: [] });

        expect(
            screen.queryByTestId("side-nav-link-manage-permissions")
        ).not.toBeInTheDocument();
    });

    it("offers User history to a FAM administrator", () => {
        renderLayout({ accessRoles: ["FAM_ADMIN"] });

        expect(screen.getByTestId("side-nav-link-user-history")).toBeInTheDocument();
    });

    it("offers User history to every other tier that administers access", () => {
        /*
            This asserted the opposite until the fixture was corrected, and
            passed only because "APP_ADMIN" and "DELEGATED_ADMIN" are not role
            names - a real one carries the integration and environment, so
            neither matched its prefix and the case being exercised was really
            the empty one. The screen asks about one application at a time and
            shows what has happened to access the caller already manages, so
            everyone who administers access is offered it. See routePaths.spec,
            which had this right.
        */
        renderLayout({
            accessRoles: ["APP_ADMIN_6538_DEV", "DELEGATED_ADMIN_6538_DEV"],
        });

        expect(
            screen.getByTestId("side-nav-link-user-history")
        ).toBeInTheDocument();
    });

    it("withholds User history from a DevOps-only administrator", () => {
        // They administer no access, so every application picker is empty.
        renderLayout({ accessRoles: ["DEVOPS_ADMIN_6538_DEV"] });

        expect(
            screen.queryByTestId("side-nav-link-user-history")
        ).not.toBeInTheDocument();
    });

    it("withholds Manage roles from everyone else", () => {
        // Presentation only - the route guard turns them away and the endpoint
        // refuses them regardless. This asserts they are not invited.
        renderLayout({
            accessRoles: ["APP_ADMIN_6538_DEV", "DELEGATED_ADMIN_6538_DEV"],
        });

        expect(
            screen.queryByTestId("side-nav-link-manage-roles")
        ).not.toBeInTheDocument();
        // The entries every signed-in user gets are still there, so this is not
        // passing merely because the nav failed to render.
        expect(
            screen.getByTestId("side-nav-link-manage-permissions")
        ).toBeInTheDocument();
        expect(
            screen.getByTestId("side-nav-link-my-permissions")
        ).toBeInTheDocument();
    });

    it("opens and closes the profile panel from the avatar button", async () => {
        // The panel is always mounted and slides in on a class, so "open" is not
        // presence in the DOM - which is what makes this worth asserting.
        const { container } = renderLayout({});
        const panelClass = () =>
            container.querySelector(".profile-panel")!.className;

        expect(panelClass()).not.toContain("profile-panel--open");

        await userEvent.click(
            screen.getByRole("button", { name: /user settings/i })
        );
        expect(panelClass()).toContain("profile-panel--open");

        await userEvent.click(screen.getByRole("button", { name: "Close" }));
        expect(panelClass()).not.toContain("profile-panel--open");
    });

    it("carries the signed-in user into the profile panel", () => {
        renderLayout({ accessRoles: ["FAM_ADMIN"] });

        expect(
            screen.getByText("Jane Doe (FAM administrator)")
        ).toBeInTheDocument();
    });
});
