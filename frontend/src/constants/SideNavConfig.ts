import type { SideNavItemType } from "@/types/SideNavTypes";
import {
    AddAppPermissionRoute,
    ManagePermissionsRoute,
    ManageRolesRoute,
} from "../router/routes";
import { isFamAdmin } from "@/utils/AdminRoleUtils";
import UserMultiple from "@carbon/icons-vue/es/user--multiple/16";
import UserRole from "@carbon/icons-vue/es/user--role/16";

export const sideNavItems: SideNavItemType[] = [
    {
        name: "Manage permissions",
        routeName: ManagePermissionsRoute.name!,
        icon: UserMultiple,
        subRoutes: [
            AddAppPermissionRoute.name!,
        ],
    },
    {
        name: "Manage roles",
        routeName: ManageRolesRoute.name!,
        icon: UserRole,
        // Defining what roles exist is a FAM administrator's power, so nobody
        // else is offered the screen. Hiding it is presentation only - the route
        // guard turns them away and the endpoint refuses them regardless.
        isVisible: isFamAdmin,
    },
];
