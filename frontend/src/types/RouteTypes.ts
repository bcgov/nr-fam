/**
 * An application is a CSS integration in a given environment, not a FAM
 * application id - one integration spans dev/test/prod, so it takes both.
 */
export type AddAppPermissionRouteProps = {
    integrationId: number;
    environment: string;
};
