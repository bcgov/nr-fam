<script setup lang="ts">
import CssPermissionsTable from "@/components/PermissionsTable/CssPermissionsTable.vue";
import TablePlaceholder from "@/components/PermissionsTable/TablePlaceholder.vue";
import Button from "@/components/UI/Button.vue";
import Dropdown from "@/components/UI/Dropdown.vue";
import PageTitle from "@/components/UI/PageTitle.vue";
import { AddAppPermissionRoute } from "@/router/routes";
import { newlyGrantedKeys as newlyGrantedKeysFor } from "@/components/PermissionsTable/utils";
import { AdminMgmtApiService } from "@/services/ApiServiceFactory";
import { selectedApp, setSelectedApp } from "@/store/ApplicationState";
import AddIcon from "@carbon/icons-vue/es/add/16";
import UserIcon from "@carbon/icons-vue/es/user/16";
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
import { useQuery, useQueryClient } from "@tanstack/vue-query";
import type { CssApplicationOptionDto } from "fam-api";
import Tab from "primevue/tab";
import TabList from "primevue/tablist";
import TabPanel from "primevue/tabpanel";
import TabPanels from "primevue/tabpanels";
import Tabs from "primevue/tabs";
import { computed, onUnmounted, ref } from "vue";
import { useRouter } from "vue-router";

/**
 * Permissions for one application.
 *
 * Laid out to match the screen this replaces: page title, application picker
 * with the add button beside it, then a raised panel holding a tabbed table.
 *
 * <b>One tab, deliberately.</b> The original carried three - users, delegated
 * administrators, application administrators - each backed by its own FAM table.
 * Those tables moved to CSS, where a delegated administrator is a role like any
 * other, so there is one list of assignments to show. The tab strip is kept
 * because it is part of the layout and because a second tab is plausible again
 * later, not to imply tabs that are missing.
 */
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
                <Tabs value="0">
                    <TabList>
                        <Tab value="0">
                            <component :is="UserIcon" />
                            Users
                        </Tab>
                    </TabList>
                    <TabPanels>
                        <TabPanel value="0">
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

    .tab-table {
        margin-top: -0.0625rem;
    }
}
</style>
