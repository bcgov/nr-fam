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
    bulkGrantDelegatedAdmins: "/manage-permissions/bulk-upload-delegated-admins",
    bulkGrantApplicationAdmins: "/manage-permissions/bulk-upload-application-admins",
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

/**
 * The how-to guides, served from public/ and opened in a new tab.
 *
 * <p>Held in the app rather than linked to a site somebody else maintains: the
 * legacy application shipped them the same way, and a guide that 404s is worse
 * than no menu item.
 *
 * <p>Built from {@code docs/guides} - Markdown, plus screenshots captured from
 * the running app - by {@code npm run guides:build}. They replaced two Word
 * documents that were dated in their filenames; the names are stable now
 * because the content is versioned with the code instead.
 */
export const HOW_TO_GUIDES = {
    applicationAdmin: "/fam-app-admin-guide.pdf",
    delegatedAdmin: "/fam-delegated-admin-guide.pdf",
} as const;

/** Whether these roles carry any authority over who holds what. */
const managesAccess = (roles: readonly string[]) =>
    roles.some(
        (role) =>
            role === FAM_ADMIN ||
            role.startsWith(APP_ADMIN_PREFIX) ||
            role.startsWith(DELEGATED_ADMIN_PREFIX)
    );

/**
 * Whether FAM has anything at all for these roles.
 *
 * <p>The four administrative kinds, and no fifth: FAM admin, application admin,
 * delegated admin, DevOps admin. Holding none of them means every screen in the
 * shell would load and then fail to fill itself, which is what used to happen -
 * a person with no roles landed on Manage permissions and met an error under the
 * application selector, phrased as though something had gone wrong rather than
 * as though they were not admitted.
 *
 * <p>Wider than {@link managesAccess}, which asks a narrower question: whether
 * somebody may change who holds what. A DevOps administrator may not, but FAM
 * still has Manage roles for them, so they are admitted here and not there.
 */
/**
 * Whether these roles belong to a delegated administrator.
 *
 * <p>They are the people the FAM Terms of Use bind, and the only ones asked to
 * accept them - see components/TermsOfUse.
 */
export const isDelegatedAdmin = (roles: readonly string[]) =>
    roles.some((role) => role.startsWith(DELEGATED_ADMIN_PREFIX));

export const hasAnyFamRole = (roles: readonly string[]) =>
    managesAccess(roles) ||
    roles.some((role) => role.startsWith(DEVOPS_ADMIN_PREFIX));

/**
 * The how-to guide for these roles, or null when none of them has one.
 *
 * <p>Two guides exist, one per kind of administrator. Somebody who is both -
 * an application administrator here, a delegated administrator there - gets the
 * application administrator's, which covers appointing administrators and
 * everything the delegated one covers.
 *
 * <p>A FAM administrator reads the application administrator's guide too: they
 * do the same job everywhere rather than a different one.
 *
 * <p>Null for a DevOps administrator holding nothing else. Neither guide is
 * about defining roles, and offering somebody a document that does not mention
 * what they came to do is worse than not offering one.
 */
export const howToGuideFor = (
    roles: readonly string[]
): string | null => {
    const holds = (prefix: string) =>
        roles.some((role) => role.startsWith(prefix));

    if (roles.includes(FAM_ADMIN) || holds(APP_ADMIN_PREFIX)) {
        return HOW_TO_GUIDES.applicationAdmin;
    }
    if (holds(DELEGATED_ADMIN_PREFIX)) {
        return HOW_TO_GUIDES.delegatedAdmin;
    }
    return null;
};

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
            it is a screen with nothing on it, offered by name.

            This used to admit somebody holding nothing at all as well, on the
            reasoning that an empty table answers "what do I administer" and an
            absent screen does not. It answered the wrong question: they were not
            asking what they administer, they were being told, in an error under
            the application selector, that something had gone wrong. Nothing had.
            They now meet /no-access before the shell renders and never reach
            this menu - see RequireAnyFamRole.

            Presentation only, as everywhere else here - the endpoints answer to
            the token, not to the menu.
        */
        isVisible: (roles) => managesAccess(roles),
        subPaths: [
            ROUTES.addAppPermission,
            ROUTES.editAppPermission,
            ROUTES.addDelegatedAdmin,
            ROUTES.addApplicationAdmin,
            ROUTES.addDevopsAdmin,
            ROUTES.bulkGrant,
            ROUTES.bulkGrantDelegatedAdmins,
            ROUTES.bulkGrantApplicationAdmins,
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
 * <p>Somebody the menu offers nothing goes to /no-access. They used to land on
 * Manage permissions and meet an error under the application selector - a screen
 * reporting a failure, when nothing had failed and they simply had no roles.
 */
export const homeRouteFor = (accessRoles: readonly string[]): string => {
    if (!hasAnyFamRole(accessRoles)) {
        return ROUTES.noAccess;
    }
    return getMenuEntries(accessRoles)[0]?.path ?? ROUTES.managePermissions;
};

/** Whether a nav entry should read as current, for it or anything under it. */
export const isMenuItemActive = (item: MenuLeaf, pathname: string): boolean =>
    pathname === item.path ||
    (item.subPaths?.some((path) => pathname.startsWith(path)) ?? false);
