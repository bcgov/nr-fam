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

    it("offers Manage roles to a FAM administrator", () => {
        renderLayout({ accessRoles: ["FAM_ADMIN"] });

        expect(screen.getByTestId("side-nav-link-manage-roles")).toBeInTheDocument();
    });

    it("withholds Manage roles from everyone else", () => {
        // Presentation only - the route guard turns them away and the endpoint
        // refuses them regardless. This asserts they are not invited.
        renderLayout({ accessRoles: ["APP_ADMIN", "DELEGATED_ADMIN"] });

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
