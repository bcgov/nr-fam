<script setup lang="ts">
import AdministratorsTable from "@/components/PermissionsTable/AdministratorsTable.vue";
import CssPermissionsTable from "@/components/PermissionsTable/CssPermissionsTable.vue";
import TablePlaceholder from "@/components/PermissionsTable/TablePlaceholder.vue";
import Button from "@/components/UI/Button.vue";
import Dropdown from "@/components/UI/Dropdown.vue";
import PageTitle from "@/components/UI/PageTitle.vue";
import {
    AddAppPermissionRoute,
    AddApplicationAdminRoute,
    AddDelegatedAdminRoute,
    BulkGrantRoute,
} from "@/router/routes";
import { newlyGrantedKeys as newlyGrantedKeysFor } from "@/components/PermissionsTable/utils";
import { AdminMgmtApiService } from "@/services/ApiServiceFactory";
import { selectedApp, setSelectedApp } from "@/store/ApplicationState";
import AddIcon from "@carbon/icons-vue/es/add/16";
import UserIcon from "@carbon/icons-vue/es/user/16";
import EnterpriseIcon from "@carbon/icons-vue/es/enterprise/16";
import HelpDeskIcon from "@carbon/icons-vue/es/help-desk/16";
import NotificationStack from "@/views/ManagePermissionsView/NotificationStack.vue";
import {
    AddAppUserPermissionSuccessQuerykey,
    type AppPermissionGrantSummary,
} from "@/views/AddAppPermission/utils";
import {
    clearNotifications,
    toGrantNotifications,
} from "@/views/ManagePermissionsView/utils";
import type { PermissionNotificationType } from "@/types/NotificationTypes";
import { fetchSelfPermissions } from "@/services/AuthApiService";
import { useQuery, useQueryClient } from "@tanstack/vue-query";
import type { CssApplicationOptionDto } from "fam-api";
import Tab from "primevue/tab";
import TabList from "primevue/tablist";
import TabPanel from "primevue/tabpanel";
import TabPanels from "primevue/tabpanels";
import Tabs from "primevue/tabs";
import { computed, onUnmounted, ref, watch } from "vue";
import { useRouter } from "vue-router";

/**
 * Permissions for one application.
 *
 * Laid out to match the screen this replaces: page title, application picker
 * with the add button beside it, then a raised panel holding a tabbed table.
 *
 * Three tabs, as the original had. An earlier note here claimed one was enough,
 * on the grounds that a delegated administrator is now "a role like any other".
 * That was wrong: the administrative roles live on <b>FAM's own</b> CSS
 * integration, not on the application's, because a token carries only the roles
 * of the client it was issued to. So administrators never appear in the
 * application's own assignment list, and the two admin tabs are a second read
 * rather than a filter over the first.
 *
 * The admin tabs are offered to application administrators and above, matching
 * both the backend guard and what the original showed.
 */
/**
 * Whether this caller may see who else administers the selected application.
 *
 * Application administrators and above, matching what the backend allows and
 * what legacy showed - a delegated administrator grants ordinary access but does
 * not appoint administrators, so the roster is not theirs to read.
 *
 * Decided from `/auth/self/permissions`, which returns the caller's roles
 * already decoded into a tier and an application. Rebuilding
 * `APP_ADMIN_<id>_<ENV>` here instead would copy the backend's naming grammar
 * into the browser, where it would drift.
 */
const selfPermissionsQuery = useQuery({
    queryKey: ["self-permissions"],
    queryFn: fetchSelfPermissions,
});

const canSeeAdminTabs = computed(() => {
    // FAM has neither tier. Its integration does carry APP_ADMIN and
    // DELEGATED_ADMIN roles, but every one of them records who administers some
    // OTHER application - that is the only place such a role can sit and still
    // reach FAM's token. A tab here would ask "who are FAM's delegated admins",
    // which is not a thing: FAM's own administrators hold FAM_ADMIN, and they
    // are on the Users tab.
    if (selectedApp.value?.fam_application) {
        return false;
    }

    const permissions = selfPermissionsQuery.data.value ?? [];
    if (permissions.some((permission) => permission.role === "FAM_ADMIN")) {
        return true;
    }
    return permissions.some(
        (permission) =>
            permission.role === "APP_ADMIN" &&
            permission.css_integration_id === selectedApp.value?.integration_id &&
            permission.environment === selectedApp.value?.environment
    );
});

/**
 * Which tab is open, held here rather than left to Tabs' own default.
 *
 * The admin tabs come and go with the chosen application, and an uncontrolled
 * Tabs keeps its value when the tab it names disappears - switching from FREP's
 * Delegated admins tab to FAM left the strip showing Users while the panel below
 * rendered nothing at all.
 */
const activeTab = ref("0");

const USERS_TAB = "0";

// Whenever the admin tabs go away, so does any selection of one.
watch(canSeeAdminTabs, (visible) => {
    if (!visible) {
        activeTab.value = USERS_TAB;
    }
});

const router = useRouter();
const queryClient = useQueryClient();

/**
 * The outcome of a grant made just before landing here.
 *
 * Read once, on the way in. The grant happens on another screen and this one is
 * where its result is reported, so it travels through the query cache rather
 * than through the route.
 */
const grantSummary =
    queryClient.getQueryData<AppPermissionGrantSummary>([
        AddAppUserPermissionSuccessQuerykey,
    ]) ?? null;

const notifications = ref<PermissionNotificationType[]>(
    toGrantNotifications(grantSummary)
);

/**
 * The rows to mark "New".
 *
 * Captured once, from the same grant the banners describe, rather than read
 * from the cache on demand - clearing the banners removes that cache entry, and
 * the highlight should outlive dismissing them.
 */
const newlyGrantedKeys = ref<string[]>(newlyGrantedKeysFor(grantSummary));

const onNotificationClose = (index: number) => {
    notifications.value.splice(index, 1);
};

// Both the banners and the data behind them, so a dismissed banner does not
// reappear the next time this screen is opened.
onUnmounted(() => clearNotifications(queryClient, notifications));

const applicationsQuery = useQuery({
    queryKey: ["css-applications"],
    queryFn: () =>
        AdminMgmtApiService.cssIntegrationsApi
            .getCssApplications()
            .then((res) => res.data),
    refetchOnMount: true,
});

const applicationOptions = computed<CssApplicationOptionDto[]>(
    () => applicationsQuery.data.value ?? []
);

const handleApplicationChange = (event: { value: CssApplicationOptionDto }) => {
    setSelectedApp(event.value);
    // The banners and the highlight both describe a grant against the
    // application that was chosen when it was made, so neither applies once a
    // different one is selected.
    clearNotifications(queryClient, notifications);
    newlyGrantedKeys.value = [];
};

const goToBulkUpload = () => {
    if (!selectedApp.value) {
        return;
    }
    router.push({
        name: BulkGrantRoute.name,
        query: {
            integrationId: selectedApp.value.integration_id,
            environment: selectedApp.value.environment,
        },
    });
};

const goToAddApplicationAdmin = () => {
    if (!selectedApp.value) {
        return;
    }
    router.push({
        name: AddApplicationAdminRoute.name,
        query: {
            integrationId: selectedApp.value.integration_id,
            environment: selectedApp.value.environment,
        },
    });
};

const goToAddDelegatedAdmin = () => {
    if (!selectedApp.value) {
        return;
    }
    router.push({
        name: AddDelegatedAdminRoute.name,
        query: {
            integrationId: selectedApp.value.integration_id,
            environment: selectedApp.value.environment,
        },
    });
};

const goToAddPermission = () => {
    if (!selectedApp.value) {
        return;
    }
    router.push({
        name: AddAppPermissionRoute.name,
        query: {
            integrationId: selectedApp.value.integration_id,
            environment: selectedApp.value.environment,
        },
    });
};
</script>

<template>
    <div class="manage-permission-view">
        <PageTitle
            title="Manage permissions"
            subtitle="Manage users and add permissions for the selected application"
        />

        <div class="row dropdown-and-button-container">
            <div class="col-lg-5 col-md-8 col-12 mb-3 mb-md-0">
                <Dropdown
                    id="application-selector-dropdown-id"
                    class="application-dropdown"
                    name="application-selector-dropdown"
                    label-text="Application:"
                    :value="selectedApp"
                    @change="handleApplicationChange"
                    :options="applicationOptions"
                    option-label="description"
                    placeholder="Choose an application to manage permissions"
                    :is-fetching="applicationsQuery.isLoading.value"
                    :is-error="applicationsQuery.isError.value"
                    error-msg="Failed to load applications from CSS. Please try again."
                />
            </div>

            <div
                class="col-lg-3 col-md-3 col-12 d-flex justify-content-md-start justify-content-center"
            >
                <Button
                    v-if="selectedApp"
                    outlined
                    label="Add permission"
                    :icon="AddIcon"
                    @click="goToAddPermission"
                />
            </div>
        </div>

        <div class="content-container">
            <NotificationStack
                :permission-notifications="notifications"
                :on-close="onNotificationClose"
            />

            <TablePlaceholder v-if="!selectedApp" />

            <div v-else class="tab-view-container">
                <Tabs v-model:value="activeTab">
                    <TabList>
                        <Tab value="0">
                            <component :is="UserIcon" />
                            Users
                        </Tab>
                        <Tab v-if="canSeeAdminTabs" value="1">
                            <component :is="EnterpriseIcon" />
                            Delegated admins
                        </Tab>
                        <Tab v-if="canSeeAdminTabs" value="2">
                            <component :is="HelpDeskIcon" />
                            Application admins
                        </Tab>
                    </TabList>
                    <TabPanels>
                        <TabPanel value="0">
                            <div class="tab-actions">
                                <Button
                                    outlined
                                    label="Bulk upload"
                                    :icon="AddIcon"
                                    @click="goToBulkUpload"
                                />
                            </div>
                            <CssPermissionsTable
                                :key="`${selectedApp.integration_id}-${selectedApp.environment}`"
                                class="tab-table"
                                :integration-id="selectedApp.integration_id"
                                :environment="selectedApp.environment"
                                :newly-granted-keys="newlyGrantedKeys"
                                :app-name="
                                    selectedApp.description ?? selectedApp.name
                                "
                            />
                        </TabPanel>

                        <TabPanel v-if="canSeeAdminTabs" value="1">
                            <!--
                                The add button lives on the tab rather than
                                beside the application picker: that one adds a
                                permission, and appointing an administrator is a
                                different act with a different screen.
                            -->
                            <div class="tab-actions">
                                <Button
                                    outlined
                                    label="Add delegated admin"
                                    :icon="AddIcon"
                                    @click="goToAddDelegatedAdmin"
                                />
                            </div>
                            <AdministratorsTable
                                :key="`delegated-${selectedApp.integration_id}-${selectedApp.environment}`"
                                class="tab-table"
                                :integration-id="selectedApp.integration_id"
                                :environment="selectedApp.environment"
                                tier="DELEGATED_ADMIN"
                                :app-name="
                                    selectedApp.description ?? selectedApp.name
                                "
                            />
                        </TabPanel>

                        <TabPanel v-if="canSeeAdminTabs" value="2">
                            <div class="tab-actions">
                                <Button
                                    outlined
                                    label="Add application admin"
                                    :icon="AddIcon"
                                    @click="goToAddApplicationAdmin"
                                />
                            </div>
                            <AdministratorsTable
                                :key="`app-${selectedApp.integration_id}-${selectedApp.environment}`"
                                class="tab-table"
                                :integration-id="selectedApp.integration_id"
                                :environment="selectedApp.environment"
                                tier="APP_ADMIN"
                                :app-name="
                                    selectedApp.description ?? selectedApp.name
                                "
                            />
                        </TabPanel>
                    </TabPanels>
                </Tabs>
            </div>
        </div>
    </div>
</template>

<style lang="scss">
.manage-permission-view {
    .application-dropdown {
        width: 100%;
    }

    .dropdown-and-button-container {
        width: 100%;
        margin-top: 2.5rem;
        display: flex;
        flex-direction: row;
        justify-content: space-between;
        align-items: end;

        .fam-button {
            width: 100%;
            height: 3rem;
        }
    }

    /*
        The add button on an admin tab, right-aligned within its pane.

        `.fam-button` needs the width reset: the dropdown row above stretches its
        button to fill a grid column, and that rule is not scoped to it, so
        without this the button here fills the pane and "right aligned" has no
        visible effect.
    */
    .tab-actions {
        display: flex;
        justify-content: flex-end;
        margin-bottom: 1rem;

        .fam-button {
            width: auto;
        }
    }

    .content-container {
        display: flex;
        flex-direction: column;
        margin: 2.5rem -2.5rem 0 -2.5rem;
        background: var(--semantic-color-surface-layer-1);
        min-height: calc(100vh - 19rem);
        padding: 2.5rem;
    }

    .p-tablist {
        .p-tab {
            height: 3rem;
        }
    }

    /*
        Forced colours - Windows High Contrast, macOS Increase contrast - throw
        away background colour and our blue active underline, and repaint every
        border black. The three tabs then render as identical black boxes with
        nothing marking the selected one.

        The cue is a thick underline in the system Highlight colour, plus weight -
        deliberately not a filled tab. Chrome paints a backplate behind text in
        this mode to guarantee contrast, which sits on top of any background we
        set and hides HighlightText labels entirely. Borders are not backplated,
        so an underline survives where a fill does not.
    */
    @media (forced-colors: active) {
        .p-tablist .p-tab {
            border: 1px solid ButtonBorder;
        }

        .p-tablist .p-tab[aria-selected="true"] {
            border-bottom: 0.25rem solid Highlight;
            font-weight: 700;
        }
    }

    .tab-table {
        margin-top: -0.0625rem;
    }
}
</style>
