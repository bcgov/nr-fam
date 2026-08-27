import type { IdpProvider } from "@/enum/IdpEnum";

export type IdpTypes = IdpProvider.IDIR | IdpProvider.BCEIDBUSINESS;

export type FamLoginUser = {
    username?: string;
    displayName?: string;
    email?: string;
    idpProvider?: string;
    organization?: string;
};

export type AuthState = {
    readonly isAuthenticated: boolean;
    readonly famLoginUser: FamLoginUser | null;
    readonly isAuthRestored: boolean;
    /**
     * The caller's FAM administrative roles, from `/auth/self`.
     *
     * Not read from the token: FAM resolves them per request so a change of
     * access takes effect without a fresh sign-in. Used to decide what the UI
     * offers - never to decide what it is allowed to do, which the backend
     * settles on its own.
     */
    readonly accessRoles: readonly string[];
};

export interface AuthContext {
    authState: AuthState;
    login: (idp: IdpTypes) => Promise<void>;
    logout: (options?: { expired?: boolean }) => Promise<void>;
    ensureFreshToken: () => Promise<void>;
    forceRefreshSession: () => Promise<void>;
    handlePostLogin: () => Promise<void>;
}
