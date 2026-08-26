import { IbmLpa, UserMultiple, UserRole } from "@carbon/icons-react";
import type { ComponentType } from "react";

/**
 * Every route the application has, and which of them appear in the side nav.
 *
 * One table rather than a router config plus a separate menu constant: FAM
 * carried both, and the pair drifted - the nav named routes by string while the
 * router named them by object, so a renamed route left a menu entry pointing at
 * nothing until somebody clicked it.
 */
export const ROUTES = {
    landing: "/",
    managePermissions: "/manage-permissions",
    addAppPermission: "/manage-permissions/add-app-permission",
    addDelegatedAdmin: "/manage-permissions/add-delegated-admin",
    addApplicationAdmin: "/manage-permissions/add-application-admin",
    bulkGrant: "/manage-permissions/bulk-upload",
    manageRoles: "/manage-roles",
    permissionHistory: "/permission-history",
    myPermissions: "/my-permissions",
    noAccess: "/no-access",
} as const;

export type MenuLeaf = {
    id: string;
    label: string;
    path: string;
    icon: ComponentType;
    /**
     * Routes that should light this entry up as well as its own.
     *
     * "Add permission" is reached from Manage permissions and belongs to it;
     * without this the nav goes blank the moment somebody starts granting.
     */
    subPaths?: string[];
    /** Whether the signed-in roles admit this entry. Absent means everyone. */
    isVisible?: (accessRoles: readonly string[]) => boolean;
};

const FAM_ADMIN = "FAM_ADMIN";

export const MENU: MenuLeaf[] = [
    {
        id: "manage-permissions",
        label: "Manage permissions",
        path: ROUTES.managePermissions,
        icon: UserMultiple,
        subPaths: [
            ROUTES.addAppPermission,
            ROUTES.addDelegatedAdmin,
            ROUTES.addApplicationAdmin,
            ROUTES.bulkGrant,
        ],
    },
    {
        id: "manage-roles",
        label: "Manage roles",
        path: ROUTES.manageRoles,
        icon: UserRole,
        // Defining what roles exist is a FAM administrator's power, so nobody
        // else is offered the screen. Hiding it is presentation only - the
        // route guard turns them away and the endpoint refuses them regardless.
        isVisible: (roles) => roles.includes(FAM_ADMIN),
    },
    {
        // Offered to everyone who can sign in: it reports on the caller, and
        // somebody who administers nothing sees an empty table rather than a
        // screen they were not allowed to open.
        id: "my-permissions",
        label: "My permissions",
        path: ROUTES.myPermissions,
        icon: IbmLpa,
    },
];

/** The menu as one set of roles sees it. */
export const getMenuEntries = (accessRoles: readonly string[]): MenuLeaf[] =>
    MENU.filter((item) => item.isVisible?.(accessRoles) ?? true);

/** Whether a nav entry should read as current, for it or anything under it. */
export const isMenuItemActive = (item: MenuLeaf, pathname: string): boolean =>
    pathname === item.path ||
    (item.subPaths?.some((path) => pathname.startsWith(path)) ?? false);
