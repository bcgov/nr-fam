<script setup lang="ts">
import CssPermissionsTable from "@/components/PermissionsTable/CssPermissionsTable.vue";
import Button from "@/components/UI/Button.vue";
import Dropdown from "@/components/UI/Dropdown.vue";
import PageTitle from "@/components/UI/PageTitle.vue";
import { AddAppPermissionRoute } from "@/router/routes";
import { AdminMgmtApiService } from "@/services/ApiServiceFactory";
import { selectedApp, setSelectedApp } from "@/store/ApplicationState";
import { useQuery } from "@tanstack/vue-query";
import type { CssApplicationOptionDto } from "fam-api";
import { computed } from "vue";
import { useRouter } from "vue-router";

/**
 * Permissions for one application.
 *
 * Applications, roles and assignments all come from CSS now. The tabs this
 * screen used to carry - FAM application admins, delegated admins, per-app
 * administrators - went with the tables that backed them, so there is one table
 * left and no tab strip.
 */
const router = useRouter();

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
    <div class="manage-permissions-container">
        <PageTitle
            title="Manage permissions"
            subtitle="Add, edit or delete user permissions"
        />

        <div class="application-select-row">
            <Dropdown
                class="application-dropdown"
                name="application"
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

            <Button
                v-if="selectedApp"
                label="Add permission"
                @click="goToAddPermission"
            />
        </div>

        <CssPermissionsTable
            v-if="selectedApp"
            :key="`${selectedApp.integration_id}-${selectedApp.environment}`"
            :integration-id="selectedApp.integration_id"
            :environment="selectedApp.environment"
        />

        <p v-else class="no-selection">
            Choose an application to see its permissions.
        </p>
    </div>
</template>

<style lang="scss">
.manage-permissions-container {
    .application-select-row {
        display: flex;
        align-items: flex-end;
        gap: 1rem;
        margin: 1.5rem 0;
    }

    .application-dropdown {
        flex: 1;
        max-width: 32rem;
    }

    .no-selection {
        color: var(--semantic-color-text-secondary);
    }
}
</style>
