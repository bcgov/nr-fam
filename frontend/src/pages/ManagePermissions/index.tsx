import {
    Add,
    Enterprise,
    HelpDesk,
    SearchLocate,
    Tools,
    User,
} from "@carbon/icons-react";
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
import { useNavigate, useSearchParams } from "react-router-dom";
import { EmptyState } from "@/components/EmptyState/EmptyState";
import { PageTitle } from "@/components/PageTitle";
import { SectionTile } from "@/components/SectionTile";
import { AdministratorsTable } from "@/components/PermissionsTable/AdministratorsTable";
import { CssPermissionsTable } from "@/components/PermissionsTable/CssPermissionsTable";
import { newlyGrantedKeys as newlyGrantedKeysFor } from "@/components/PermissionsTable/utils";
import { useSelectedApp } from "@/context/application/useSelectedApp";
import { useNotification } from "@/context/notification/useNotification";
import { ROUTES } from "@/routes/routePaths";
import { matchesTypedTextBeside } from "@/utils/ComboBoxFilter";
import { AdminMgmtApiService } from "@/services/ApiServiceFactory";
import { fetchSelfPermissions } from "@/services/AuthApiService";
import {
    AddAppUserPermissionErrorQuerykey,
    AddAppUserPermissionSuccessQuerykey,
    type AppPermissionGrantSummary,
} from "@/pages/AddAppPermission/grantUtils";
import { GrantFailureList } from "./GrantFailureList";
import {
    isPermissionsTab,
    tabIndexOf,
    toGrantBanners,
    visibleTabs,
    type PermissionBanner,
    type PermissionsTab,
} from "./utils";
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

export const ManagePermissions: FC = () => {
    const navigate = useNavigate();
    const [searchParams] = useSearchParams();
    const queryClient = useQueryClient();
    const { selectedApp, setSelectedApp } = useSelectedApp();
    const { display } = useNotification();

    /*
        Which tab, by name. Restored from the query string so that appointing an
        administrator returns to the tab it was started from: the add screens are
        separate routes, so coming back is a remount, and an index in state would
        start again at Users every time.
    */
    const [activeTabName, setActiveTabName] = useState<PermissionsTab>(() => {
        const requested = searchParams.get("tab");
        return isPermissionsTab(requested) ? requested : "users";
    });

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

    /*
        The DevOps tab is a FAM administrator's alone - not an application
        administrator's, as the other two are.

        A DevOps admin decides what roles an application has. That is not
        authority an application administrator holds, so they cannot hand it out
        either; letting them would be a way to acquire it by proxy. The endpoint
        refuses them regardless - this only stops offering it.
    */
    const canSeeDevopsTab = useMemo(() => {
        if (selectedApp?.fam_application) {
            return false;
        }
        return (selfPermissionsQuery.data ?? []).some(
            (permission) => permission.role === "FAM_ADMIN"
        );
    }, [selectedApp, selfPermissionsQuery.data]);

    /*
        The strip as this caller sees it, and where the chosen tab sits in it.

        Derived rather than corrected after the fact. Two effects used to reset a
        stored index whenever the tab it named disappeared - switching from
        FREP's Delegated admins tab to FAM otherwise left the strip on Users with
        nothing rendered under it. Deriving the index from the name means the
        selection can never point past the end of the strip in the first place,
        and it survives the moment before `self-permissions` answers, when the
        tab restored from the URL is not yet one this caller has.
    */
    const tabs = useMemo(
        () => visibleTabs(canSeeAdminTabs, canSeeDevopsTab),
        [canSeeAdminTabs, canSeeDevopsTab]
    );
    const activeTab = tabIndexOf(activeTabName, tabs);

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
            // So the screen can send them back to the tab they left, rather than
            // to the top of the strip.
            tab: activeTabName,
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
                        /*
                            Carbon shows the whole list otherwise. Beside the
                            selection, because Carbon leaves the chosen
                            application's name in the box and a plain filter
                            would narrow the list to it on reopening.
                        */
                        shouldFilterItem={matchesTypedTextBeside(
                            selectedApp?.description ?? selectedApp?.name
                        )}
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
                                setActiveTabName(tabs[selectedIndex] ?? "users")
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
                                {canSeeDevopsTab ? (
                                    <Tab renderIcon={Tools}>DevOps admins</Tab>
                                ) : null}
                            </TabList>
                            <TabPanels>
                                <TabPanel>
                                    <SectionTile
                                        title={`${appName} users`}
                                        icon={User}
                                        description={`Who has access to ${appName}, and what each person can do in it`}
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
                                                    kind="primary"
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
                                            description={`Who can grant access to ${appName}, and which roles they can grant`}
                                            actions={
                                                <Button
                                                    kind="primary"
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
                                            description={`Who can grant any role in ${appName}, and appoint delegated admins`}
                                            actions={
                                                <Button
                                                    kind="primary"
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

                                {canSeeDevopsTab ? (
                                    <TabPanel>
                                        <SectionTile
                                            title="DevOps admins"
                                            icon={Tools}
                                            description={`Who can define and remove the roles of ${appName}`}
                                            actions={
                                                <Button
                                                    kind="primary"
                                                    size="md"
                                                    renderIcon={Add}
                                                    onClick={() =>
                                                        goTo(ROUTES.addDevopsAdmin)
                                                    }
                                                >
                                                    Add DevOps admin
                                                </Button>
                                            }
                                        >
                                            <AdministratorsTable
                                                key={`devops-${appKey}`}
                                                integrationId={
                                                    selectedApp.integration_id
                                                }
                                                environment={
                                                    selectedApp.environment
                                                }
                                                tier="DEVOPS_ADMIN"
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
