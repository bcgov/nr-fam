import type { Component } from "vue";
import type { RouteRecordName } from "vue-router";

/**
 * Type definition for a side navigation item.
 *
 * @property {string} name - The display name of the navigation item.
 * @property {RouteRecordName} routeName - The route name registered with vue-router.
 * @property {string} [icon] - Optional. The icon import from @carbon/icons-vue,
 *                             example: require("@carbon/icons-vue/es/user--multiple/16"
 * @property {SideNavItemType[]} [subMenuItems] - Optional array of submenu items.
 *                                                Each submenu item follows the same structure as `SideNavItemType`,
 *                                                allowing for nested navigation.
 * @property {string[]} subRoutes - Optional array of sub-route's name. If the current URL matches any sub-route
 *                                     (e.g., `link` is `/a/` and a sub-route is `/a/apple`),
 *                                     the parent link (`/a/`) will be considered active.
 * @property {boolean} [disabled=false] - Optional flag to disable the navigation item. Default is false.
 * @property {() => boolean} [isVisible] - Optional predicate deciding whether to offer the item
 *                                         at all. Evaluated on render rather than at module load,
 *                                         so an item gated on the caller's roles appears once
 *                                         those are known. Presentation only: the route guard and
 *                                         the backend both re-check.
 */
export type SideNavItemType = {
    name: string;
    routeName: RouteRecordName;
    icon?: Component;
    subMenuItems?: SideNavItemType[];
    subRoutes?: RouteRecordName[];
    disabled?: boolean;
    isVisible?: () => boolean;
};
