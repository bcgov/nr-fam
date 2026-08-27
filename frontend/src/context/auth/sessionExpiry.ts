/**
 * The note the idle logout leaves for the sign-in screen.
 *
 * Signing out goes through Keycloak: the browser leaves the app, ends the realm
 * session and comes back to the landing page as a fresh page load. Nothing in
 * memory survives that, so "you were logged out because you went idle" has to be
 * written somewhere the round trip cannot clear.
 *
 * sessionStorage, not localStorage: the note belongs to this tab and this
 * sign-out. In localStorage it would outlive the tab and greet somebody in a
 * different window with an explanation for something that never happened to
 * them.
 */
const SESSION_EXPIRED_KEY = "fam.sessionExpired";

/** Called just before an idle logout, never before a deliberate one. */
export const markSessionExpired = (): void => {
    try {
        window.sessionStorage.setItem(SESSION_EXPIRED_KEY, "1");
    } catch {
        // Storage disabled or full. The sign-out still has to happen; the
        // person simply arrives at the sign-in screen without the explanation.
    }
};

/**
 * Whether this visit to the sign-in screen followed an idle logout, clearing
 * the note as it reads it.
 *
 * Read-and-clear, so refreshing the sign-in page does not keep re-announcing a
 * sign-out that happened once.
 */
export const consumeSessionExpired = (): boolean => {
    try {
        if (window.sessionStorage.getItem(SESSION_EXPIRED_KEY) === "1") {
            window.sessionStorage.removeItem(SESSION_EXPIRED_KEY);
            return true;
        }
    } catch {
        // Unreadable storage means no note, which is the same as no note.
    }
    return false;
};
