import { Add, Enterprise, HelpDesk, SearchLocate, User } from "@carbon/icons-react";
import {
    Button,
    ComboBox,
    Tab,
    TabList,
    TabPanel,
    TabPanels,
    Tabs,
} from "@carbon/react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import type { CssApplicationOptionDto } from "fam-api";
import { useEffect, useMemo, useState, type FC } from "react";
import { useNavigate } from "react-router-dom";
import { EmptyState } from "@/components/EmptyState/EmptyState";
import { PageTitle } from "@/components/PageTitle";
import { SectionTile } from "@/components/SectionTile";
import { AdministratorsTable } from "@/components/PermissionsTable/AdministratorsTable";
import { CssPermissionsTable } from "@/components/PermissionsTable/CssPermissionsTable";
import { newlyGrantedKeys as newlyGrantedKeysFor } from "@/components/PermissionsTable/utils";
import { useSelectedApp } from "@/context/application/useSelectedApp";
import { useNotification } from "@/context/notification/useNotification";
import { ROUTES } from "@/routes/routePaths";
import { AdminMgmtApiService } from "@/services/ApiServiceFactory";
import { fetchSelfPermissions } from "@/services/AuthApiService";
import {
    AddAppUserPermissionErrorQuerykey,
    AddAppUserPermissionSuccessQuerykey,
    type AppPermissionGrantSummary,
} from "@/pages/AddAppPermission/grantUtils";
import { GrantFailureList } from "./GrantFailureList";
import { toGrantBanners, type PermissionBanner } from "./utils";
import "./ManagePermissions.css";

/**
 * Permissions for one application.
 *
 * Laid out to match the screen this replaces: page title, application picker
 * with the add button beside it, then a raised panel holding a tabbed table.
 *
 * Three tabs, as the original had. An earlier note claimed one was enough, on
 * the grounds that a delegated administrator is now "a role like any other".
 * That was wrong: the administrative roles live on <b>FAM's own</b> CSS
 * integration, not on the application's, because a token carries only the roles
 * of the client it was issued to. So administrators never appear in the
 * application's own assignment list, and the two admin tabs are a second read
 * rather than a filter over the first.
 */

const USERS_TAB = 0;

export const ManagePermissions: FC = () => {
    const navigate = useNavigate();
    const queryClient = useQueryClient();
    const { selectedApp, setSelectedApp } = useSelectedApp();
    const { display } = useNotification();

    const [activeTab, setActiveTab] = useState(USERS_TAB);

    /**
     * The outcome of a grant made just before landing here.
     *
     * Read once, on the way in. The grant happens on another screen and this one
     * is where its result is reported, so it travels through the query cache
     * rather than through the route.
     */
    const [banners] = useState<PermissionBanner[]>(() =>
        toGrantBanners(
            queryClient.getQueryData<AppPermissionGrantSummary>([
                AddAppUserPermissionSuccessQuerykey,
            ]) ?? null
        )
    );

    /**
     * The rows to mark "New".
     *
     * Captured once, from the same grant the banners describe, rather than read
     * from the cache on demand - clearing the banners removes that cache entry,
     * and the highlight should outlive dismissing them.
     */
    const [newlyGrantedKeys, setNewlyGrantedKeys] = useState<string[]>(() =>
        newlyGrantedKeysFor(
            queryClient.getQueryData<AppPermissionGrantSummary>([
                AddAppUserPermissionSuccessQuerykey,
            ]) ?? null
        )
    );

    /**
     * Drop the banners and the data behind them.
     *
     * Both halves matter: without removing the cached outcome the same banner
     * reappears on the next visit to this screen, having already been dismissed.
     */
    const clearGrantOutcome = () => {
        queryClient.removeQueries({
            queryKey: [AddAppUserPermissionSuccessQuerykey],
        });
        queryClient.removeQueries({
            queryKey: [AddAppUserPermissionErrorQuerykey],
        });
    };

    /*
        Raised once, on arrival. These are the only reports on this screen that
        are not about something the person just did here - the grant happened on
        another page - so they are read out of the cache rather than fired from a
        handler, and an effect is what turns that state into the event a toast is.
    */
    useEffect(() => {
        banners.forEach((banner) =>
            display({
                kind: banner.kind,
                title: banner.title,
                subtitle: banner.subtitle,
                // Errors and warnings ignore this and wait to be dismissed,
                // which is what a list somebody has to act on needs.
                timeout: 0,
                children: banner.outcomes ? (
                    <GrantFailureList outcomes={banner.outcomes} />
                ) : undefined,
            })
        );
    }, [banners, display]);

    // On the way out, so a dismissed report does not reappear next time.
    useEffect(() => clearGrantOutcome, []); // eslint-disable-line react-hooks/exhaustive-deps

    const applicationsQuery = useQuery({
        queryKey: ["css-applications"],
        queryFn: () =>
            AdminMgmtApiService.cssIntegrationsApi
                .getCssApplications()
                .then((res) => res.data),
        refetchOnMount: true,
    });

    /**
     * Whether this caller may see who else administers the selected application.
     *
     * Application administrators and above, matching what the backend allows and
     * what legacy showed - a delegated administrator grants ordinary access but
     * does not appoint administrators, so the roster is not theirs to read.
     *
     * Decided from `/auth/self/permissions`, which returns the caller's roles
     * already decoded into a tier and an application. Rebuilding
     * `APP_ADMIN_<id>_<ENV>` here instead would copy the backend's naming
     * grammar into the browser, where it would drift.
     */
    const selfPermissionsQuery = useQuery({
        queryKey: ["self-permissions"],
        queryFn: fetchSelfPermissions,
    });

    const canSeeAdminTabs = useMemo(() => {
        // FAM has neither tier. Its integration does carry APP_ADMIN and
        // DELEGATED_ADMIN roles, but every one of them records who administers
        // some OTHER application - that is the only place such a role can sit
        // and still reach FAM's token. A tab here would ask "who are FAM's
        // delegated admins", which is not a thing: FAM's own administrators hold
        // FAM_ADMIN, and they are on the Users tab.
        if (selectedApp?.fam_application) {
            return false;
        }

        const permissions = selfPermissionsQuery.data ?? [];
        if (permissions.some((permission) => permission.role === "FAM_ADMIN")) {
            return true;
        }
        return permissions.some(
            (permission) =>
                permission.role === "APP_ADMIN" &&
                permission.css_integration_id === selectedApp?.integration_id &&
                permission.environment === selectedApp?.environment
        );
    }, [selectedApp, selfPermissionsQuery.data]);

    // Whenever the admin tabs go away, so does any selection of one. An
    // uncontrolled Tabs keeps its index when the tab it names disappears -
    // switching from FREP's Delegated admins tab to FAM left the strip showing
    // Users while the panel below rendered nothing at all.
    useEffect(() => {
        if (!canSeeAdminTabs) {
            setActiveTab(USERS_TAB);
        }
    }, [canSeeAdminTabs]);

    const handleApplicationChange = (app: CssApplicationOptionDto | null) => {
        setSelectedApp(app ?? undefined);
        // The banners and the highlight both describe a grant against the
        // application that was chosen when it was made, so neither applies once
        // a different one is selected.
        clearGrantOutcome();
        setNewlyGrantedKeys([]);
    };

    /** Every grant screen is reached with the application on the query string. */
    const goTo = (path: string) => {
        if (!selectedApp) {
            return;
        }
        const params = new URLSearchParams({
            integrationId: String(selectedApp.integration_id),
            environment: selectedApp.environment,
        });
        navigate(`${path}?${params.toString()}`);
    };

    const appName = selectedApp
        ? (selectedApp.description ?? selectedApp.name)
        : "";

    /** Remounts the tables when the application changes, so no rows carry over. */
    const appKey = selectedApp
        ? `${selectedApp.integration_id}-${selectedApp.environment}`
        : "none";

    return (
        <div className="manage-permission-view">
            <PageTitle
                title="Manage permissions"
                subtitle="Manage users and add permissions for the selected application"
            />

            <div className="dropdown-and-button-container">
                <div className="application-dropdown">
                    <ComboBox
                        id="application-selector-dropdown-id"
                        titleText="Application:"
                        placeholder="Choose an application to manage permissions"
                        items={applicationsQuery.data ?? []}
                        itemToString={(item: CssApplicationOptionDto | null) =>
                            item?.description ?? item?.name ?? ""
                        }
                        selectedItem={selectedApp ?? null}
                        onChange={({ selectedItem }) =>
                            handleApplicationChange(selectedItem ?? null)
                        }
                        disabled={applicationsQuery.isLoading}
                        invalid={applicationsQuery.isError}
                        invalidText="Failed to load applications from CSS. Please try again."
                    />
                </div>
            </div>

            <div className="content-container">
                {!selectedApp ? (
                    <EmptyState
                        title="Nothing to show yet!"
                        body={
                            <>
                                Choose an application to view its users.
                                <br />
                                The list will display here.
                            </>
                        }
                        icon={<SearchLocate size={48} />}
                    />
                ) : (
                    <div className="tab-view-container">
                        <Tabs
                            selectedIndex={activeTab}
                            onChange={({ selectedIndex }) =>
                                setActiveTab(selectedIndex)
                            }
                        >
                            <TabList aria-label="Permissions" contained>
                                <Tab renderIcon={User}>Users</Tab>
                                {canSeeAdminTabs ? (
                                    <Tab renderIcon={Enterprise}>
                                        Delegated admins
                                    </Tab>
                                ) : null}
                                {canSeeAdminTabs ? (
                                    <Tab renderIcon={HelpDesk}>
                                        Application admins
                                    </Tab>
                                ) : null}
                            </TabList>
                            <TabPanels>
                                <TabPanel>
                                    <SectionTile
                                        title={`${appName} users`}
                                        icon={User}
                                        description={`This table shows all the users in ${appName} and their permissions levels`}
                                        actions={
                                            <>
                                                <Button
                                                    kind="tertiary"
                                                    size="md"
                                                    renderIcon={Add}
                                                    onClick={() =>
                                                        goTo(ROUTES.bulkGrant)
                                                    }
                                                >
                                                    Bulk upload
                                                </Button>
                                                <Button
                                                    kind="tertiary"
                                                    size="md"
                                                    renderIcon={Add}
                                                    onClick={() =>
                                                        goTo(
                                                            ROUTES.addAppPermission
                                                        )
                                                    }
                                                >
                                                    Add permission
                                                </Button>
                                            </>
                                        }
                                    >
                                        <CssPermissionsTable
                                            key={`users-${appKey}`}
                                            integrationId={
                                                selectedApp.integration_id
                                            }
                                            environment={selectedApp.environment}
                                            newlyGrantedKeys={newlyGrantedKeys}
                                            appName={appName}
                                        />
                                    </SectionTile>
                                </TabPanel>

                                {canSeeAdminTabs ? (
                                    <TabPanel>
                                        {/*
                                            Appointing an administrator is a
                                            different act from granting a
                                            permission, with a different screen -
                                            so the button belongs to this section
                                            rather than to the page.
                                        */}
                                        <SectionTile
                                            title="Delegated admins"
                                            icon={Enterprise}
                                            description={`Who may grant roles in ${appName}, and which roles they may grant`}
                                            actions={
                                                <Button
                                                    kind="tertiary"
                                                    size="md"
                                                    renderIcon={Add}
                                                    onClick={() =>
                                                        goTo(
                                                            ROUTES.addDelegatedAdmin
                                                        )
                                                    }
                                                >
                                                    Add delegated admin
                                                </Button>
                                            }
                                        >
                                            <AdministratorsTable
                                                key={`delegated-${appKey}`}
                                                integrationId={
                                                    selectedApp.integration_id
                                                }
                                                environment={
                                                    selectedApp.environment
                                                }
                                                tier="DELEGATED_ADMIN"
                                                appName={appName}
                                            />
                                        </SectionTile>
                                    </TabPanel>
                                ) : null}

                                {canSeeAdminTabs ? (
                                    <TabPanel>
                                        <SectionTile
                                            title="Application admins"
                                            icon={HelpDesk}
                                            description={`Who may administer ${appName}, including appointing delegated admins`}
                                            actions={
                                                <Button
                                                    kind="tertiary"
                                                    size="md"
                                                    renderIcon={Add}
                                                    onClick={() =>
                                                        goTo(
                                                            ROUTES.addApplicationAdmin
                                                        )
                                                    }
                                                >
                                                    Add application admin
                                                </Button>
                                            }
                                        >
                                            <AdministratorsTable
                                                key={`app-${appKey}`}
                                                integrationId={
                                                    selectedApp.integration_id
                                                }
                                                environment={
                                                    selectedApp.environment
                                                }
                                                tier="APP_ADMIN"
                                                appName={appName}
                                            />
                                        </SectionTile>
                                    </TabPanel>
                                ) : null}
                            </TabPanels>
                        </Tabs>
                    </div>
                )}
            </div>
        </div>
    );
};

export default ManagePermissions;
