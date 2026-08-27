import { beforeEach, describe, expect, it, vi } from "vitest";
import { consumeSessionExpired, markSessionExpired } from "./sessionExpiry";

/**
 * The note an idle logout leaves for the sign-in screen.
 *
 * It has to survive a full round trip out to Keycloak and back, which is a fresh
 * page load - nothing in memory lives through that - and it has to be read
 * exactly once.
 */
describe("session expiry note", () => {
    beforeEach(() => window.sessionStorage.clear());

    it("says nothing when the sign-out was deliberate", () => {
        expect(consumeSessionExpired()).toBe(false);
    });

    it("survives being written and read back, as it must across the redirect", () => {
        markSessionExpired();
        expect(consumeSessionExpired()).toBe(true);
    });

    it("is read once, so refreshing the sign-in page does not repeat it", () => {
        markSessionExpired();

        expect(consumeSessionExpired()).toBe(true);
        // The second read is the page refresh. Announcing the same sign-out
        // again would suggest it had happened twice.
        expect(consumeSessionExpired()).toBe(false);
    });

    it("does not break when storage is unavailable", () => {
        // Private browsing, or a locked-down profile. The sign-out still has to
        // happen; only the explanation is lost.
        const setItem = vi
            .spyOn(Storage.prototype, "setItem")
            .mockImplementation(() => {
                throw new Error("QuotaExceededError");
            });
        const getItem = vi
            .spyOn(Storage.prototype, "getItem")
            .mockImplementation(() => {
                throw new Error("SecurityError");
            });

        expect(() => markSessionExpired()).not.toThrow();
        expect(consumeSessionExpired()).toBe(false);

        setItem.mockRestore();
        getItem.mockRestore();
    });
});
