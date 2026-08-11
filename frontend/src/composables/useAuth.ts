import { inject } from "vue";
import { useRouter } from "vue-router";
import { getUserManager } from "@/services/keycloak";
import { AUTH_KEY } from "@/constants/InjectionKeys";
import type { AuthContext } from "@/types/AuthTypes";

/**
 * Utility function to access the AuthContext. If no context is provided,
 * clears the stored session and redirects to the landing page.
 * @returns {AuthContext} The injected authentication state and functions.
 * @throws {Error} If the AuthProvider is not present in the component tree.
 */
const useAuth = (): AuthContext => {
    const auth = inject<AuthContext>(AUTH_KEY);
    const router = useRouter(); // Vue Router for redirection

    if (!auth) {
        // If auth context is missing, log out and redirect to landing page
        getUserManager()
            .removeUser()
            .then(() => {
                router.push("/");
            })
            .catch((error) => {
                console.error("Failed to log out:", error);
            });

        throw new Error(
            "AuthProvider is missing. Logging out and redirecting to the landing page."
        );
    }

    return auth;
};

export default useAuth;
