import { createContext } from "react";
import type { AuthState, IdpTypes } from "@/types/AuthTypes";

export type AuthContextValue = {
    authState: AuthState;
    login: (idp: IdpTypes) => Promise<void>;
    /**
     * Ends the session.
     *
     * `expired` marks it as an idle timeout rather than a deliberate sign-out,
     * which is what puts the explanation on the sign-in screen afterwards.
     */
    logout: (options?: { expired?: boolean }) => Promise<void>;
    /** Renews the access token only if it is at or near expiry. */
    ensureFreshToken: () => Promise<void>;
    /** Renews now, rotating the refresh token. Throws if it has gone. */
    forceRefreshSession: () => Promise<void>;
};

export const AuthContext = createContext<AuthContextValue | undefined>(
    undefined
);
