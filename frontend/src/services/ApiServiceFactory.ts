import { EnvironmentSettings } from "@/services/EnvironmentSettings";
import axios from "axios";
import {
    AdminUserAccessesApi,
    Configuration,
    FAMAccessControlPrivilegesApi,
    FAMApplicationAdminApi,
    FAMApplicationsApi,
    FAMForestClientsApi,
    FAMUserRoleAssignmentApi,
    FAMUserTermsAndConditionsApi,
    IDIRBCeIDProxyApi,
    PermissionAuditApi,
} from "fam-api";
import { TEN_SECONDS } from "@/constants/TimeUnits";

/*
 * FAM used to run two APIs on separate base URLs. They are one service now, so
 * every API class below is built against the same base URL.
 *
 * The two exported groupings are kept because they mirror how the UI is
 * organised - the admin screens versus the permission screens - not because
 * there are still two backends.
 */
type AppAccessControlApiType = {
    applicationsApi: FAMApplicationsApi;
    userRoleAssignmentApi: FAMUserRoleAssignmentApi;
    forestClientsApi: FAMForestClientsApi;
    idirBceidProxyApi: IDIRBCeIDProxyApi;
    userTermsAndConditionsApi: FAMUserTermsAndConditionsApi;
    permissionAuditApi: PermissionAuditApi;
};

type AdminManagementApiType = {
    applicationAdminApi: FAMApplicationAdminApi;
    delegatedAdminApi: FAMAccessControlPrivilegesApi;
    adminUserAccessesApi: AdminUserAccessesApi;
};

axios.defaults.headers.common["Content-Type"] = "application/json";
axios.defaults.timeout = TEN_SECONDS;

export default class ApiServiceFactory {
    private static instance: ApiServiceFactory;

    private environmentSettings: EnvironmentSettings;
    private appAccessControlApiService: AppAccessControlApiType;
    private adminManagementApiService: AdminManagementApiType;

    /*
    Note, this class is a singleton; so the constructor is private.
    */
    private constructor() {
        this.environmentSettings = new EnvironmentSettings();
        const baseURL = this.environmentSettings.getApiBaseUrl();

        this.appAccessControlApiService = {
            applicationsApi: this.createApiInstance(
                FAMApplicationsApi,
                baseURL
            ),
            userRoleAssignmentApi: this.createApiInstance(
                FAMUserRoleAssignmentApi,
                baseURL
            ),
            forestClientsApi: this.createApiInstance(
                FAMForestClientsApi,
                baseURL
            ),
            idirBceidProxyApi: this.createApiInstance(
                IDIRBCeIDProxyApi,
                baseURL
            ),
            userTermsAndConditionsApi: this.createApiInstance(
                FAMUserTermsAndConditionsApi,
                baseURL
            ),
            permissionAuditApi: this.createApiInstance(
                PermissionAuditApi,
                baseURL
            ),
        };

        this.adminManagementApiService = {
            applicationAdminApi: this.createApiInstance(
                FAMApplicationAdminApi,
                baseURL
            ),
            delegatedAdminApi: this.createApiInstance(
                FAMAccessControlPrivilegesApi,
                baseURL
            ),
            adminUserAccessesApi: this.createApiInstance(
                AdminUserAccessesApi,
                baseURL
            ),
        };
    }

    public static getInstance(): ApiServiceFactory {
        if (!ApiServiceFactory.instance) {
            ApiServiceFactory.instance = new ApiServiceFactory();
        }
        return ApiServiceFactory.instance;
    }

    getAppAccessControlApiService() {
        return this.appAccessControlApiService;
    }

    getAdminManagementApiService() {
        return this.adminManagementApiService;
    }

    /**
     * 'private' method using Typescript Generics, to instantiate Axios API(s) for this service provider.
     * @param c required class Types, the intended API 'class' to be instantiated.
     * @param baseURL optional, API's base URL (domain, and path if required).
     *                Will be set to `configuration` if baseURL is passed in.
     *                Note, for now, only the `baseURL` is the intended option. Also see
     *                `why` baseURL is set here at comment from @HttpCommon:defaultAxiosConfig.
     * @returns API class instantiated.
     */
    private createApiInstance<C>(
        // Class Types in Generics: see Typescript ref - https://www.typescriptlang.org/docs/handbook/2/generics.html
        // Use a flexible constructor signature to avoid Axios type identity conflicts that arise when
        // generated client packages bundle their own axios copy (different module path = different TS type).
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        c: new (...args: any[]) => C,
        baseURL?: string
    ): C {
        const configuration = baseURL
            ? ({ baseOptions: { baseURL } } as Configuration)
            : undefined;
        return new c(configuration, "", axios);
    }
}

export const apiServiceProvider = ApiServiceFactory.getInstance();
export const AdminMgmtApiService =
    apiServiceProvider.getAdminManagementApiService();
export const AppActlApiService =
    apiServiceProvider.getAppAccessControlApiService();
