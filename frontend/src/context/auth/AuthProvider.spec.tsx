import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

/*
    Sign-out, which had no test until now.

    The SessionTimeout suite asserts that the idle guard *calls* logout; it mocks
    the function itself, so what logout actually does to the realm session was
    only ever verified by reading it. That is the wrong thing to leave unpinned:
    a sign-out that clears local state but leaves the realm session standing
    looks completely correct from the inside - the app shows the sign-in screen -
    and is only visible when the next sign-in walks straight back in without a
    prompt.
*/

const signoutRedirect = vi.fn();
const removeUser = vi.fn();
const navigate = vi.fn();
const assign = vi.fn();

vi.mock("@/services/keycloak", () => ({
    AUTH_CALLBACK_PATH: "/authCallback",
    KC_IDP_HINT: { IDIR: "azureidir", BCEIDBUSINESS: "bceidbusiness" },
    getUserManager: () => ({ signoutRedirect, removeUser }),
    loadStoredUser: vi.fn(async () => null),
    ensureFreshToken: vi.fn(async () => null),
    forceRenew: vi.fn(async () => null),
}));

vi.mock("@/services/AuthApiService", () => ({
    bootstrapLogin: vi.fn(async () => ({ data: {} })),
    fetchSelf: vi.fn(async () => ({ data: {} })),
}));

vi.mock("react-router-dom", async () => {
    const actual =
        await vi.importActual<typeof import("react-router-dom")>(
            "react-router-dom"
        );
    return { ...actual, useNavigate: () => navigate };
});

import { AuthProvider } from "@/context/auth/AuthProvider";
import { useAuth } from "@/context/auth/useAuth";
import { consumeSessionExpired } from "@/context/auth/sessionExpiry";
import { MemoryRouter } from "react-router-dom";

const SignOutButtons = () => {
    const { logout } = useAuth();
    return (
        <>
            <button onClick={() => void logout()}>deliberate</button>
            <button onClick={() => void logout({ expired: true })}>idle</button>
        </>
    );
};

const renderProvider = () =>
    render(
        <MemoryRouter>
            <AuthProvider>
                <SignOutButtons />
            </AuthProvider>
        </MemoryRouter>
    );

describe("logout", () => {
    beforeEach(() => {
        vi.clearAllMocks();
        window.localStorage.clear();
        window.sessionStorage.clear();
        signoutRedirect.mockResolvedValue(undefined);
        removeUser.mockResolvedValue(undefined);
        Object.defineProperty(window, "location", {
            configurable: true,
            value: { assign, href: "http://localhost:3000/" },
        });
    });

    it("ends the realm session rather than only the local one", async () => {
        renderProvider();

        await userEvent.click(await screen.findByText("deliberate"));

        // RP-initiated logout. Dropping the tokens locally would leave the realm
        // session alive and the next sign-in would not prompt.
        await waitFor(() => expect(signoutRedirect).toHaveBeenCalledTimes(1));
    });

    it("leaves the stored user in place, so Keycloak can attribute the sign-out", async () => {
        renderProvider();

        await userEvent.click(await screen.findByText("deliberate"));
        await waitFor(() => expect(signoutRedirect).toHaveBeenCalled());

        /*
            oidc-client-ts reads `id_token_hint` off the stored user and removes
            it itself. Removing it first sends a logout the realm cannot match to
            a session, which it then declines to end - the exact failure this
            ordering exists to avoid.
        */
        expect(removeUser).not.toHaveBeenCalled();
    });

    it("notes an idle expiry, and says nothing when the person chose to leave", async () => {
        renderProvider();

        await userEvent.click(await screen.findByText("idle"));
        await waitFor(() => expect(signoutRedirect).toHaveBeenCalled());
        expect(consumeSessionExpired()).toBe(true);

        vi.clearAllMocks();
        window.sessionStorage.clear();

        await userEvent.click(screen.getByText("deliberate"));
        await waitFor(() => expect(signoutRedirect).toHaveBeenCalled());
        expect(consumeSessionExpired()).toBe(false);
    });

    it("still leaves this browser signed out when the redirect fails", async () => {
        // The realm session may survive - we cannot reach it - but the person
        // must not be left sitting on a page they believe they have left.
        signoutRedirect.mockRejectedValue(new Error("realm unreachable"));
        renderProvider();

        await userEvent.click(await screen.findByText("deliberate"));

        await waitFor(() => expect(removeUser).toHaveBeenCalledTimes(1));
        expect(assign).toHaveBeenCalledWith("/");
    });
});
