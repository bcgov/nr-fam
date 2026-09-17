import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { AuthContext, type AuthContextValue } from "@/context/auth/AuthContext";
import { TermsOfUse } from "./index";

/**
 * The Terms of Use gate. What matters is that the only ways out are the two
 * choices it offers, and that a failed acceptance does not throw anybody out.
 */

const logout = vi.fn(async () => {});
const acceptTermsOfUse = vi.fn(async () => {});

const authValue = (): AuthContextValue => ({
    authState: {
        isAuthenticated: true,
        famLoginUser: { username: "JSMITH" },
        isAuthRestored: true,
        accessRoles: ["DELEGATED_ADMIN_22264_DEV__FREP_EDITOR"],
        requiresAcceptTc: true,
    },
    login: async () => {},
    logout,
    ensureFreshToken: async () => {},
    forceRefreshSession: async () => {},
    acceptTermsOfUse,
});

const renderTerms = () =>
    render(
        <AuthContext.Provider value={authValue()}>
            <TermsOfUse />
        </AuthContext.Provider>
    );

describe("TermsOfUse", () => {
    beforeEach(() => {
        logout.mockClear();
        acceptTermsOfUse.mockReset().mockResolvedValue(undefined);
    });

    it("shows the terms with the section numbering of the approved copy", () => {
        renderTerms();
        expect(screen.getByText("FAM Terms of use")).toBeInTheDocument();
        expect(screen.getByText("Suspension and Termination")).toBeInTheDocument();
        expect(
            screen.getByRole("link", { name: "download a copy (PDF)" })
        ).toHaveAttribute("href", "/2024-06-04-fam-terms-conditions.pdf");
    });

    it("records acceptance when accepted", async () => {
        renderTerms();
        await userEvent.click(
            screen.getByRole("button", { name: "I accept the Terms of Use" })
        );
        expect(acceptTermsOfUse).toHaveBeenCalledTimes(1);
        expect(logout).not.toHaveBeenCalled();
    });

    it("signs out without recording anything when declined", async () => {
        renderTerms();
        await userEvent.click(
            screen.getByRole("button", { name: "Cancel and log out" })
        );
        expect(logout).toHaveBeenCalledTimes(1);
        expect(acceptTermsOfUse).not.toHaveBeenCalled();
    });

    it("ignores Escape", async () => {
        renderTerms();
        await userEvent.keyboard("{Escape}");
        expect(screen.getByText("FAM Terms of use")).toBeInTheDocument();
        expect(logout).not.toHaveBeenCalled();
    });

    it("says so and stays open when acceptance fails", async () => {
        vi.spyOn(console, "error").mockImplementation(() => {});
        acceptTermsOfUse.mockRejectedValueOnce(new Error("boom"));
        renderTerms();

        await userEvent.click(
            screen.getByRole("button", { name: "I accept the Terms of Use" })
        );

        await waitFor(() =>
            expect(
                screen.getByText("We couldn't record your acceptance.")
            ).toBeInTheDocument()
        );
        expect(logout).not.toHaveBeenCalled();
    });
});
