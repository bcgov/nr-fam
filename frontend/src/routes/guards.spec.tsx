import { render, screen } from "@testing-library/react";
import type { ReactNode } from "react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { describe, expect, it } from "vitest";
import { AuthContext, type AuthContextValue } from "@/context/auth/AuthContext";
import type { AuthState } from "@/types/AuthTypes";
import {
    RedirectIfSignedIn,
    RequireAuth,
    RequireFamAdmin,
    RequireRoleManager,
} from "./guards";
import { ROUTES } from "./routePaths";

/**
 * The guards decide from auth state alone, so the state is supplied directly
 * rather than by driving a sign-in. What is being tested is the decision.
 */
const withAuth = (state: Partial<AuthState>, children: ReactNode) => {
    const value: AuthContextValue = {
        authState: {
            isAuthenticated: false,
            famLoginUser: null,
            isAuthRestored: true,
            accessRoles: [],
            ...state,
        },
        login: async () => {},
        logout: async () => {},
        ensureFreshToken: async () => {},
        forceRefreshSession: async () => {},
    };
    return (
        <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
    );
};

/** Renders the guard at `/secret`, with named pages at every destination. */
const renderGuarded = (state: Partial<AuthState>, guarded: ReactNode) =>
    render(
        withAuth(
            state,
            <MemoryRouter initialEntries={["/secret"]}>
                <Routes>
                    <Route path="/secret" element={guarded} />
                    <Route path={ROUTES.landing} element={<p>landing page</p>} />
                    <Route path={ROUTES.noAccess} element={<p>no access page</p>} />
                    <Route
                        path={ROUTES.managePermissions}
                        element={<p>manage permissions page</p>}
                    />
                </Routes>
            </MemoryRouter>
        )
    );

const PROTECTED = <p>protected content</p>;

describe("RequireAuth", () => {
    it("renders the page for a signed-in user", () => {
        renderGuarded(
            { isAuthenticated: true },
            <RequireAuth>{PROTECTED}</RequireAuth>
        );

        expect(screen.getByText("protected content")).toBeInTheDocument();
    });

    it("sends a signed-out user to the landing page", () => {
        renderGuarded(
            { isAuthenticated: false },
            <RequireAuth>{PROTECTED}</RequireAuth>
        );

        expect(screen.getByText("landing page")).toBeInTheDocument();
        expect(screen.queryByText("protected content")).not.toBeInTheDocument();
    });

    it("decides nothing until the session has been restored", () => {
        // The state a page refresh passes through: not yet authenticated only
        // because the stored token has not been read back. Redirecting here
        // would bounce a signed-in user to the landing page on every reload.
        renderGuarded(
            { isAuthenticated: false, isAuthRestored: false },
            <RequireAuth>{PROTECTED}</RequireAuth>
        );

        expect(screen.queryByText("protected content")).not.toBeInTheDocument();
        expect(screen.queryByText("landing page")).not.toBeInTheDocument();
    });
});

describe("RequireFamAdmin", () => {
    it("renders the page for a FAM administrator", () => {
        renderGuarded(
            { isAuthenticated: true, accessRoles: ["FAM_ADMIN"] },
            <RequireFamAdmin>{PROTECTED}</RequireFamAdmin>
        );

        expect(screen.getByText("protected content")).toBeInTheDocument();
    });

    it("sends a signed-in non-administrator to no-access", () => {
        // Not the landing page: the session is valid, so RedirectIfSignedIn
        // would send them straight back and the two would flicker.
        renderGuarded(
            { isAuthenticated: true, accessRoles: ["APP_ADMIN"] },
            <RequireFamAdmin>{PROTECTED}</RequireFamAdmin>
        );

        expect(screen.getByText("no access page")).toBeInTheDocument();
        expect(screen.queryByText("protected content")).not.toBeInTheDocument();
    });

    it("sends a signed-out user to the landing page rather than no-access", () => {
        renderGuarded(
            { isAuthenticated: false, accessRoles: [] },
            <RequireFamAdmin>{PROTECTED}</RequireFamAdmin>
        );

        expect(screen.getByText("landing page")).toBeInTheDocument();
    });

    it("waits for the roles rather than judging an empty list", () => {
        // The bug this exists for: roles arrive after authentication, and a
        // guard that read them in between sent a FAM administrator to
        // /no-access on every refresh.
        renderGuarded(
            { isAuthenticated: true, accessRoles: [], isAuthRestored: false },
            <RequireFamAdmin>{PROTECTED}</RequireFamAdmin>
        );

        expect(screen.queryByText("no access page")).not.toBeInTheDocument();
        expect(screen.queryByText("protected content")).not.toBeInTheDocument();
    });
});

describe("RedirectIfSignedIn", () => {
    it("shows the landing page to a signed-out visitor", () => {
        renderGuarded(
            { isAuthenticated: false },
            <RedirectIfSignedIn>{PROTECTED}</RedirectIfSignedIn>
        );

        expect(screen.getByText("protected content")).toBeInTheDocument();
    });

    it("sends a signed-in user on to manage permissions", () => {
        renderGuarded(
            { isAuthenticated: true },
            <RedirectIfSignedIn>{PROTECTED}</RedirectIfSignedIn>
        );

        expect(screen.getByText("manage permissions page")).toBeInTheDocument();
    });
});

describe("RequireRoleManager", () => {
    it("lets a DevOps administrator through", () => {
        // They manage the roles of the applications they were appointed for, so
        // the screen has to open - the picker on it offers only those.
        renderGuarded({
                isAuthenticated: true,
                accessRoles: ["DEVOPS_ADMIN_6538_DEV"],
            }, (
            <RequireRoleManager>
                <p>manage roles</p>
            </RequireRoleManager>
        ));

        expect(screen.getByText("manage roles")).toBeInTheDocument();
    });

    it("lets a FAM administrator through", () => {
        renderGuarded({ isAuthenticated: true, accessRoles: ["FAM_ADMIN"] }, (
            <RequireRoleManager>
                <p>manage roles</p>
            </RequireRoleManager>
        ));

        expect(screen.getByText("manage roles")).toBeInTheDocument();
    });

    it("turns away an application administrator", () => {
        // Handing out what an application defines is not the same as deciding
        // what it defines.
        renderGuarded({ isAuthenticated: true, accessRoles: ["APP_ADMIN_6538_DEV"] }, (
            <RequireRoleManager>
                <p>manage roles</p>
            </RequireRoleManager>
        ));

        expect(screen.queryByText("manage roles")).not.toBeInTheDocument();
    });
});

