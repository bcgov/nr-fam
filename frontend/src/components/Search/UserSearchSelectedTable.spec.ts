import { mount } from "@vue/test-utils";
import { describe, expect, it } from "vitest";
import UserSearchSelectedTable from "./UserSearchSelectedTable.vue";

/**
 * The chosen-users table on the add-permission screens.
 *
 * Its border, radius and surface come from the shared `fam-table` class, the
 * same one the role and scope tables below it carry. That is styling rather than
 * behaviour, but it is styling held by a class name - drop the class and the
 * table renders fine, just with no edge, which nothing else here would catch.
 */
const USER = {
    userId: "JSMITH",
    firstName: "Jane",
    lastName: "Smith",
    email: "jane@gov.bc.ca",
    guid: "AABB1122",
} as any;

const mountTable = (users = [USER]) =>
    mount(UserSearchSelectedTable, { props: { users } });

describe("UserSearchSelectedTable", () => {
    it("carries the shared table class, like the tables below it", () => {
        const wrapper = mountTable();

        expect(wrapper.find(".p-datatable").classes()).toContain("fam-table");
    });

    it("keeps its own class as well", () => {
        // fam-table brings the border; user-table is what sizes it and its
        // action column. Losing either is a silent visual regression.
        const wrapper = mountTable();

        expect(wrapper.find(".p-datatable").classes()).toContain("user-table");
    });

    it("draws no table at all when nobody is selected", () => {
        const wrapper = mountTable([]);

        expect(wrapper.find(".p-datatable").exists()).toBe(false);
    });

    it("reports the user to remove by id", async () => {
        const wrapper = mountTable();

        await wrapper.find("button[title='Delete user']").trigger("click");

        expect(wrapper.emitted("selected-user-deleted")?.[0]).toEqual(["JSMITH"]);
    });
});
