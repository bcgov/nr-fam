import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { AuthContext, type AuthContextValue } from "@/context/auth/AuthContext";
import type { AuthState, FamLoginUser } from "@/types/AuthTypes";
import { NoAccess } from "./index";

const renderPage = (famLoginUser: FamLoginUser | null = null) => {
    const logout = vi.fn(async () => {});
    const authState: AuthState = {
        isAuthenticated: true,
        famLoginUser,
        isAuthRestored: true,
        accessRoles: [],
    };
    const value = {
        authState,
        login: async () => {},
        logout,
        ensureFreshToken: async () => {},
        forceRefreshSession: async () => {},
    } as AuthContextValue;

    render(
        <AuthContext.Provider value={value}>
            <NoAccess />
        </AuthContext.Provider>
    );
    return { logout };
};

describe("NoAccess", () => {
    it("says they have no access, rather than reporting a failure", () => {
        renderPage();

        expect(
            screen.getByText("You do not have access in FAM")
        ).toBeInTheDocument();
        expect(
            screen.getByText("Ask a FAM administrator to grant you access.")
        ).toBeInTheDocument();
    });

    it("names the account that was judged", () => {
        /*
            Which identity this is matters when somebody holds two. An IDIR and
            a BCeID reach the same screen, and "you do not have access" alone
            does not tell them they signed in with the wrong one.
        */
        renderPage({ displayName: "Jane Smith", username: "JSMITH" });

        expect(
            screen.getByText(/You're signed in as Jane Smith/)
        ).toBeInTheDocument();
    });

    it("falls back to the username when there is no display name", () => {
        renderPage({ username: "JSMITH" });

        expect(screen.getByText(/You're signed in as JSMITH/)).toBeInTheDocument();
    });

    it("still says something useful when it knows no name at all", () => {
        // The page must not render "signed in as undefined".
        renderPage(null);

        expect(
            screen.getByText(
                "This account has not been granted any FAM administrative role."
            )
        ).toBeInTheDocument();
        expect(screen.queryByText(/signed in as/)).not.toBeInTheDocument();
    });

    it("offers signing out, and nothing else", () => {
        /*
            There is no self-service path to a role, so any other button would
            be one that cannot do anything. Signing out is the only action that
            changes the situation.
        */
        const { logout } = renderPage({ displayName: "Jane Smith" });

        const buttons = screen.getAllByRole("button");
        expect(buttons).toHaveLength(1);
        expect(buttons[0]).toHaveTextContent("Sign out");
        return userEvent.click(buttons[0]).then(() => {
            expect(logout).toHaveBeenCalledTimes(1);
        });
    });
});
