<script setup lang="ts">
import UserPermissionHistoryTable from "@/components/UserPermissionHistoryTable/index.vue";
import BreadCrumbs from "@/components/UI/BreadCrumbs.vue";
import Button from "@/components/UI/Button.vue";
import PageTitle from "@/components/UI/PageTitle.vue";
import { ManagePermissionsRoute } from "@/router/routes";
import type { BreadCrumbType } from "@/types/BreadCrumbTypes";
import type { UserType } from "fam-api";
import { useRouter } from "vue-router";

/**
 * One user's permission history for one application.
 *
 * Reached from the permissions table. Keyed on the user's GUID rather than their
 * name: the audit trail keeps no foreign key into any user record, so that it
 * survives the user being renamed or removed.
 */
const props = defineProps<{
    targetUserGuid: string;
    targetUserType: UserType;
    integrationId: number;
    environment: string;
    userName: string;
}>();

const router = useRouter();

const crumbs: BreadCrumbType[] = [
    { routeName: ManagePermissionsRoute.name as string, label: "Manage permissions" },
];

const navigateBack = () => router.push({ name: ManagePermissionsRoute.name });
</script>

<template>
    <div class="user-detail-page-container">
        <BreadCrumbs :crumbs="crumbs" />

        <PageTitle
            class="user-detail-page-title"
            title="Permissions History"
            :subtitle="`Check ${props.userName}'s permission history`"
        />

        <div class="gray-container">
            <UserPermissionHistoryTable
                :target-user-guid="props.targetUserGuid"
                :target-user-type="props.targetUserType"
                :integration-id="props.integrationId"
                :environment="props.environment"
            />

            <div class="back-button-container">
                <Button
                    label="Back"
                    severity="secondary"
                    @click="navigateBack"
                />
            </div>
        </div>
    </div>
</template>

<style lang="scss">
.user-detail-page-container {
    .user-detail-page-title {
        margin-bottom: 1.5rem;
    }

    .gray-container {
        display: flex;
        flex-direction: column;
        margin: 2.5rem -2.5rem 0 -2.5rem;
        background: var(--semantic-color-surface-layer-1);
        min-height: calc(100vh - 19rem);
        padding: 2.5rem;
    }

    .back-button-container {
        margin-top: 2rem;
    }
}
</style>
