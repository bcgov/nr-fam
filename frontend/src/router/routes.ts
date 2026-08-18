/*
 When adding a new route:
 1. Define the route as a constant object using `RouteRecordRaw`.
 2. Use `meta: { layout: "ProtectedLayout" }` if the route requires the ProtectedLayout.
 3. Add the new route constant to the `routeItems` array below.
 4. Each route must have a name defined.
*/
import type { RouteRecordRaw } from "vue-router";
import type { AddAppPermissionRouteProps } from "@/types/RouteTypes";

const protectedLayoutMeta = { layout: "ProtectedLayout" };

export const LandingRoute: RouteRecordRaw = {
    path: "/",
    name: "Landing",
    component: () => import("@/views/LandingView"),
};

export const ManagePermissionsRoute: RouteRecordRaw = {
    path: "/manage-permissions",
    name: "ManagePermissions",
    component: () => import("@/views/ManagePermissionsView"),
    meta: protectedLayoutMeta,
};

/**
 * Route to a page for granting access to app user and delegated admin
 */
export const AddAppPermissionRoute: RouteRecordRaw = {
    path: "/manage-permissions/add-app-permission",
    component: () => import("@/views/AddAppPermission"),
    props: (route): AddAppPermissionRouteProps => ({
        integrationId: Number(route.query.integrationId),
        environment: String(route.query.environment ?? ""),
    }),
    name: "AddAppPermission",
    meta: protectedLayoutMeta,
};

/**
 * Appoint a delegated administrator for one role.
 *
 * A separate screen from Add permission rather than a mode of it: the fields
 * are the same but the question is not - here the role and its scope are what
 * the appointee may hand out, not what they receive.
 *
 * Guarded by the ordinary auth guard; the backend restricts appointing to
 * application administrators and above.
 */
export const AddDelegatedAdminRoute: RouteRecordRaw = {
    path: "/manage-permissions/add-delegated-admin",
    component: () => import("@/views/AddDelegatedAdmin"),
    props: (route): AddAppPermissionRouteProps => ({
        integrationId: Number(route.query.integrationId),
        environment: String(route.query.environment ?? ""),
    }),
    name: "AddDelegatedAdmin",
    meta: protectedLayoutMeta,
};

/**
 * Appoint an application administrator.
 *
 * Shorter than the delegated equivalent because the tier is broader: an
 * application administrator is authorised over the application, so there is no
 * role to choose.
 */
export const AddApplicationAdminRoute: RouteRecordRaw = {
    path: "/manage-permissions/add-application-admin",
    component: () => import("@/views/AddApplicationAdmin"),
    props: (route): AddAppPermissionRouteProps => ({
        integrationId: Number(route.query.integrationId),
        environment: String(route.query.environment ?? ""),
    }),
    name: "AddApplicationAdmin",
    meta: protectedLayoutMeta,
};

/**
 * Route to a page for defining the roles an application offers.
 *
 * FAM administrators only - see `famAdminGuard`. Granting an existing role and
 * deciding which roles exist are different powers, and only the second one is
 * limited to FAM administrators.
 */
export const ManageRolesRoute: RouteRecordRaw = {
    path: "/manage-roles",
    name: "ManageRoles",
    component: () => import("@/views/ManageRoles"),
    meta: protectedLayoutMeta,
};

/**
 * One user's permission history for one application.
 *
 * Identified by GUID rather than name: the audit trail holds no foreign key
 * into any user record, so a renamed or removed user still has a history.
 */
export const UserPermissionHistoryRoute: RouteRecordRaw = {
    path: "/permission-history",
    name: "UserPermissionHistory",
    component: () => import("@/views/UserPermissionHistory"),
    props: (route) => ({
        targetUserGuid: String(route.query.targetUserGuid ?? ""),
        integrationId: Number(route.query.integrationId),
        environment: String(route.query.environment ?? ""),
        userName: String(route.query.userName ?? ""),
    }),
    meta: protectedLayoutMeta,
};

/**
 * What the signed-in user may administer.
 *
 * No guard beyond the ordinary auth one: it reports on the caller, so there is
 * nothing here that another administrator's permissions could leak through, and
 * somebody who administers nothing gets an empty table rather than a refusal.
 */
export const MyPermissionsRoute: RouteRecordRaw = {
    path: "/my-permissions",
    name: "MyPermissions",
    component: () => import("@/views/MyPermissions"),
    meta: protectedLayoutMeta,
};

export const NoAccessRoute: RouteRecordRaw = {
    path: "/no-access",
    name: "NoAccess",
    component: () => import("@/views/NoAccess"),
};

export const UnkownRoute: RouteRecordRaw = {
    path: "/:catchAll(.*)",
    component: () => import("@/views/NotFound/index.vue"),
};

export const routeItems: RouteRecordRaw[] = [
    LandingRoute,
    ManagePermissionsRoute,
    AddAppPermissionRoute,
    AddDelegatedAdminRoute,
    AddApplicationAdminRoute,
    ManageRolesRoute,
    UserPermissionHistoryRoute,
    MyPermissionsRoute,
    NoAccessRoute,
    UnkownRoute,
];
