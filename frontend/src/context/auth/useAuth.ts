import { useContext } from "react";
import { AuthContext } from "./AuthContext";

/**
 * Reads the auth state, refusing to guess when there is none.
 *
 * The Vue version logged the caller out and redirected when the context was
 * missing, which turned a wiring mistake into a sign-out that looked like an
 * expired session. A component outside the provider is a bug in the tree, so it
 * says so.
 */
export const useAuth = () => {
    const context = useContext(AuthContext);
    if (!context) {
        throw new Error("useAuth must be used inside an AuthProvider.");
    }
    return context;
};
