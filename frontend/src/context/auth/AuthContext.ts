import { createContext } from "react";
import type { AuthState, IdpTypes } from "@/types/AuthTypes";

export type AuthContextValue = {
    authState: AuthState;
    login: (idp: IdpTypes) => Promise<void>;
    logout: () => Promise<void>;
};

export const AuthContext = createContext<AuthContextValue | undefined>(
    undefined
);
