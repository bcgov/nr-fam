import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { ReactNode } from "react";
import { describe, expect, it, vi } from "vitest";
import { AuthContext, type AuthContextValue } from "@/context/auth/AuthContext";
import type { AuthState } from "@/types/AuthTypes";
import HeaderPanelProfile from "./index";

const renderPanel = (
    state: Partial<AuthState>,
    logout = vi.fn(async () => {})
) => {
    const value: AuthContextValue = {
        authState: {
            isAuthenticated: true,
            famLoginUser: null,
            isAuthRestored: true,
            accessRoles: [],
            ...state,
        },
        login: async () => {},
        logout,
    };
    const ui: ReactNode = (
        <AuthContext.Provider value={value}>
            <HeaderPanelProfile />
        </AuthContext.Provider>
    );
    render(ui);
    return { logout };
};

describe("HeaderPanelProfile", () => {
    it("names an IDIR user and their provider", () => {
        renderPanel({
            famLoginUser: {
                username: "JSMITH",
                displayName: "Smith, John WLRS:EX",
                email: "john.smith@gov.bc.ca",
                idpProvider: "idir",
            },
        });

        expect(screen.getByText(/Smith, John WLRS:EX/)).toBeInTheDocument();
        expect(screen.getByText("IDIR: JSMITH")).toBeInTheDocument();
        expect(screen.getByText("Email: john.smith@gov.bc.ca")).toBeInTheDocument();
    });

    it("shows the business name for a Business BCeID user", () => {
        // IDIR sessions carry no business name, so the line is absent rather
        // than empty - see the IDIR case below.
        renderPanel({
            famLoginUser: {
                username: "CONTRACTOR",
                displayName: "Jane Doe",
                idpProvider: "bceidbusiness",
                organization: "Timber Co",
            },
        });

        expect(
            screen.getByText("Business BCeID: CONTRACTOR")
        ).toBeInTheDocument();
        expect(screen.getByText("Organization: Timber Co")).toBeInTheDocument();
    });

    it("omits the organization line when there is no business", () => {
        renderPanel({
            famLoginUser: { username: "JSMITH", idpProvider: "idir" },
        });

        expect(screen.queryByText(/^Organization:/)).not.toBeInTheDocument();
    });

    it("names the most privileged role rather than the first one held", () => {
        // A FAM administrator is usually an application administrator too;
        // naming the lesser of the two would understate who they are.
        renderPanel({
            famLoginUser: { displayName: "Jane Doe" },
            accessRoles: ["APP_ADMIN", "FAM_ADMIN"],
        });

        expect(
            screen.getByText("Jane Doe (FAM administrator)")
        ).toBeInTheDocument();
    });

    it("names the user plainly when they hold no administrative role", () => {
        renderPanel({
            famLoginUser: { displayName: "Jane Doe" },
            accessRoles: [],
        });

        expect(screen.getByText("Jane Doe")).toBeInTheDocument();
    });

    it("falls back to the raw claim for an unrecognised provider", () => {
        // Better an unfamiliar alias than a confident label naming the wrong
        // identity provider.
        renderPanel({
            famLoginUser: { username: "SOMEONE", idpProvider: "bcsc" },
        });

        expect(screen.getByText("bcsc: SOMEONE")).toBeInTheDocument();
    });

    it("signs the user out", async () => {
        const { logout } = renderPanel({
            famLoginUser: { displayName: "Jane Doe" },
        });

        await userEvent.click(screen.getByText("Log out"));

        expect(logout).toHaveBeenCalledTimes(1);
    });
});
