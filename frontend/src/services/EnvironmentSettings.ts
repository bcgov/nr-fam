import { getEnvData } from '@/utils/EnvUtils';

export enum DeployEnv {
    TOOLS = 'tools',
    DEV = 'dev',
    TEST = 'test',
    PROD = 'prod'
}

export class EnvironmentSettings {
    env: any;

    private environmentDisplayNameKey: string = 'fam_environment_display_name';


    constructor() {
        this.env = getEnvData();
        // Deployment environment (dev / test / prod)
        const environment = this.env?.target_env.value as string;
        if (
            environment &&
            (environment == DeployEnv.DEV ||
                environment == DeployEnv.TEST ||
                environment == DeployEnv.TOOLS)
        ) {
            this.setEnvironmentDisplayName(environment);
        } else {
            this.setEnvironmentDisplayName(''); // environment == 'prod'
        }
    }

    // BC Gov SSO realm issuer, e.g.
    // https://dev.loginproxy.gov.bc.ca/auth/realms/standard
    getKeycloakIssuerUri(): string {
        return this.env?.keycloak_issuer_uri.value;
    }

    // FAM's own Keycloak client id. The backend requires it as the token's `azp`
    // on every internal API call.
    getKeycloakClientId(): string {
        return this.env?.keycloak_client_id.value;
    }

    // App origin the browser lands on after login/logout redirects.
    getFrontEndRedirectBaseUrl(): string {
        return this.env?.front_end_redirect_base_url.value;
    }

    /**
     * Base URL of the FAM backend.
     *
     * Upstream FAM ran two APIs - app-access-control and admin-management - on
     * separate base URLs. They are one service now, so there is one accessor.
     */
    getApiBaseUrl(): string {
        return this.env?.fam_api_base_url.value || 'http://localhost:3000';
    }

    getEnvironmentDisplayName(prefix = '', suffix = ''): string {
        const environmentDisplayName = window.localStorage.getItem(
            this.environmentDisplayNameKey
        ) as string;
        if (environmentDisplayName.length == 0) {
            // For production we don't want to display anything for the environment so leave the display name blank.
            return environmentDisplayName;
        } else {
            return prefix + environmentDisplayName + suffix;
        }
    }

    setEnvironmentDisplayName(name: string) {
        window.localStorage.setItem(this.environmentDisplayNameKey, name);
    }

    isDevEnvironment() {
        return this.env?.target_env.value == DeployEnv.DEV
    }

    isProdEnvironment() {
        return this.env?.target_env.value == DeployEnv.PROD
    }

}
