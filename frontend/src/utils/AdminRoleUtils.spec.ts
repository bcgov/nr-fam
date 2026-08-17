import { authState } from "@/providers/authState";
import { beforeEach, describe, expect, it } from "vitest";
import { isFamAdmin } from "./AdminRoleUtils";

const signedInWith = (accessRoles: string[]) => {
    authState.value = {
        isAuthenticated: true,
        famLoginUser: null,
        isAuthRestored: true,
        accessRoles,
    };
};

describe("isFamAdmin", () => {
    beforeEach(() => signedInWith([]));

    it("is true for a FAM administrator", () => {
        signedInWith(["FAM_ADMIN"]);
        expect(isFamAdmin()).toBe(true);
    });

    it("is false for an application or delegated administrator", () => {
        // Those tiers administer an application; they do not define what roles
        // that application has.
        signedInWith(["APP_ADMIN_22264_DEV", "DELEGATED_ADMIN_22264_DEV"]);
        expect(isFamAdmin()).toBe(false);
    });

    it("is false before the roles have been fetched", () => {
        // /auth/self answers after sign-in, so this is the state on first paint.
        // Defaulting to false means admin-only navigation appears once known,
        // rather than showing and then vanishing.
        expect(isFamAdmin()).toBe(false);
    });

    it("does not match a role that merely starts with FAM_ADMIN", () => {
        signedInWith(["FAM_ADMIN_READONLY"]);
        expect(isFamAdmin()).toBe(false);
    });
});
