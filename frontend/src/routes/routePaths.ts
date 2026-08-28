import { IbmLpa, Time, UserMultiple, UserRole } from "@carbon/icons-react";
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
    editAppPermission: "/manage-permissions/edit-app-permission",
    addDelegatedAdmin: "/manage-permissions/add-delegated-admin",
    addApplicationAdmin: "/manage-permissions/add-application-admin",
    addDevopsAdmin: "/manage-permissions/add-devops-admin",
    bulkGrant: "/manage-permissions/bulk-upload",
    manageRoles: "/manage-roles",
    permissionHistory: "/permission-history",
    userHistory: "/user-history",
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
const DEVOPS_ADMIN_PREFIX = "DEVOPS_ADMIN_";
const APP_ADMIN_PREFIX = "APP_ADMIN_";
const DELEGATED_ADMIN_PREFIX = "DELEGATED_ADMIN_";

/** Whether these roles carry any authority over who holds what. */
const managesAccess = (roles: readonly string[]) =>
    roles.some(
        (role) =>
            role === FAM_ADMIN ||
            role.startsWith(APP_ADMIN_PREFIX) ||
            role.startsWith(DELEGATED_ADMIN_PREFIX)
    );

export const MENU: MenuLeaf[] = [
    {
        id: "manage-permissions",
        label: "Manage permissions",
        path: ROUTES.managePermissions,
        icon: UserMultiple,
        /*
            Withheld from somebody whose only authority is over roles.

            A DevOps administrator manages no access, so the application picker
            on that screen is empty for them and every tab under it would be -
            it is a screen with nothing on it, offered by name. Anyone who
            administers access still gets it, including somebody who holds
            nothing at all: an empty table is a fair answer to "what do I
            administer", where an absent screen is not.

            Presentation only, as everywhere else here - the endpoints answer to
            the token, not to the menu.
        */
        isVisible: (roles) => managesAccess(roles) || !roles.some(
            (role) => role.startsWith(DEVOPS_ADMIN_PREFIX)
        ),
        subPaths: [
            ROUTES.addAppPermission,
            ROUTES.editAppPermission,
            ROUTES.addDelegatedAdmin,
            ROUTES.addApplicationAdmin,
            ROUTES.addDevopsAdmin,
            ROUTES.bulkGrant,
        ],
    },
    {
        id: "manage-roles",
        label: "Manage roles",
        path: ROUTES.manageRoles,
        icon: UserRole,
        /*
            Defining what roles exist is a FAM administrator's power, and a
            DevOps administrator's for the applications they were appointed
            for - the role name carries the application, so holding any one of
            them is enough to be offered the screen. Which applications it then
            lists is decided on the screen itself.

            Hiding it is presentation only: the route guard turns others away
            and the endpoint refuses them regardless.
        */
        isVisible: (roles) =>
            roles.includes(FAM_ADMIN) ||
            roles.some((role) => role.startsWith(DEVOPS_ADMIN_PREFIX)),
    },
    {
        id: "user-history",
        label: "User history",
        path: ROUTES.userHistory,
        icon: Time,
        /*
            Anyone who administers access somewhere. The screen asks about one
            application at a time and shows what has happened to access the
            caller already manages, so it tells them nothing they could not
            otherwise see - the endpoint checks the application they name.

            Withheld from a DevOps administrator holding nothing else, on the
            same ground as Manage permissions: they administer no access, so
            every application picker is empty for them.
        */
        isVisible: (roles) => managesAccess(roles),
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

/**
 * Where a signed-in user starts.
 *
 * <p>The first entry their own roles admit, rather than a fixed route: Manage
 * permissions is hidden from a DevOps administrator, and sending them to it
 * anyway would land them on a screen with nothing on it and no nav entry
 * pointing back at it.
 *
 * <p>Falls back to Manage permissions for somebody the menu offers nothing -
 * which is where they went before, and where the empty-state text lives.
 */
export const homeRouteFor = (accessRoles: readonly string[]): string =>
    getMenuEntries(accessRoles)[0]?.path ?? ROUTES.managePermissions;

/** Whether a nav entry should read as current, for it or anything under it. */
export const isMenuItemActive = (item: MenuLeaf, pathname: string): boolean =>
    pathname === item.path ||
    (item.subPaths?.some((path) => pathname.startsWith(path)) ?? false);
