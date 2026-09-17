import { render, screen } from "@testing-library/react";
import type { ReactNode } from "react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { describe, expect, it } from "vitest";
import { AuthContext, type AuthContextValue } from "@/context/auth/AuthContext";
import type { AuthState } from "@/types/AuthTypes";
import {
    RedirectIfSignedIn,
    RequireAnyFamRole,
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
        acceptTermsOfUse: async () => {},
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

    it("sends a signed-in administrator on to manage permissions", () => {
        renderGuarded(
            { isAuthenticated: true, accessRoles: ["FAM_ADMIN"] },
            <RedirectIfSignedIn>{PROTECTED}</RedirectIfSignedIn>
        );

        expect(screen.getByText("manage permissions page")).toBeInTheDocument();
    });

    it("sends a signed-in user with no role to the no-access page", () => {
        // Not to Manage permissions, which used to load and then report an
        // error under an application selector it could not fill.
        renderGuarded(
            { isAuthenticated: true, accessRoles: [] },
            <RedirectIfSignedIn>{PROTECTED}</RedirectIfSignedIn>
        );

        expect(screen.getByText("no access page")).toBeInTheDocument();
    });
});

describe("RequireAnyFamRole", () => {
    /*
        The gate on the whole shell. Each of the four administrative kinds is
        admitted; anything else is not, whatever else the token carries.
    */
    it.each([
        ["a FAM administrator", "FAM_ADMIN"],
        ["an application administrator", "APP_ADMIN_6538_DEV"],
        ["a delegated administrator", "DELEGATED_ADMIN_6538_DEV"],
        ["a delegated administrator of one role", "DELEGATED_ADMIN_6538_DEV__READER"],
        ["a DevOps administrator", "DEVOPS_ADMIN_6538_DEV"],
    ])("lets %s through", (_who, role) => {
        renderGuarded(
            { isAuthenticated: true, accessRoles: [role] },
            <RequireAnyFamRole>{PROTECTED}</RequireAnyFamRole>
        );

        expect(screen.getByText("protected content")).toBeInTheDocument();
    });

    it("turns away a signed-in user holding no FAM role", () => {
        renderGuarded(
            { isAuthenticated: true, accessRoles: [] },
            <RequireAnyFamRole>{PROTECTED}</RequireAnyFamRole>
        );

        expect(screen.getByText("no access page")).toBeInTheDocument();
        expect(screen.queryByText("protected content")).not.toBeInTheDocument();
    });

    it("turns away somebody carrying only an application's own role", () => {
        // A downstream application's role is not authority over FAM.
        renderGuarded(
            { isAuthenticated: true, accessRoles: ["REPT_VIEWER"] },
            <RequireAnyFamRole>{PROTECTED}</RequireAnyFamRole>
        );

        expect(screen.getByText("no access page")).toBeInTheDocument();
    });

    it("sends a signed-out visitor to the landing page, not to no-access", () => {
        // No session is a different answer from a session with nothing in it.
        renderGuarded(
            { isAuthenticated: false },
            <RequireAnyFamRole>{PROTECTED}</RequireAnyFamRole>
        );

        expect(screen.getByText("landing page")).toBeInTheDocument();
    });

    it("decides nothing until the session has been judged", () => {
        // The window that once sent an administrator to /no-access on refresh.
        renderGuarded(
            { isAuthenticated: true, isAuthRestored: false, accessRoles: [] },
            <RequireAnyFamRole>{PROTECTED}</RequireAnyFamRole>
        );

        expect(screen.queryByText("no access page")).not.toBeInTheDocument();
        expect(screen.queryByText("protected content")).not.toBeInTheDocument();
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

