<script setup lang="ts">
import TableSkeleton from "@/components/Skeletons/TableSkeleton.vue";
import { TABLE_DATATABLE_PT } from "@/passthrough/datatable/datatablePassThrough";
import Chip from "@/components/UI/Chip.vue";
import ErrorText from "@/components/UI/ErrorText.vue";
import { AppActlApiService } from "@/services/ApiServiceFactory";
import { utcToLocalDateTime } from "@/utils/DateUtils";
import { useQuery } from "@tanstack/vue-query";
import { type PermissionAuditHistoryDto, type UserType } from "fam-api";
import Column from "primevue/column";
import DataTable from "primevue/datatable";
import { computed } from "vue";

/**
 * One user's permission history for one application.
 *
 * The trail is FAM's own: CSS records who holds what, but nothing about how it
 * came to be that way, so this is the only place a grant or a revocation is
 * recorded at all.
 */
const props = defineProps<{
    targetUserGuid: string;
    /**
     * The user's directory. The audit stores the target as `IDIR\<guid>`, so the
     * GUID alone does not identify a row - the two directories number their
     * people separately.
     */
    targetUserType: UserType;
    integrationId: number;
    environment: string;
}>();

const headers = ["Date", "Activity", "Details", "Performed by"];

const historyQuery = useQuery({
    queryKey: computed(() => [
        "permission-audit-history",
        props.targetUserGuid,
        props.targetUserType,
        props.integrationId,
        props.environment,
    ]),
    queryFn: () =>
        AppActlApiService.permissionAuditApi
            .getPermissionAuditHistoryByUserAndApplication(
                props.targetUserGuid,
                props.targetUserType,
                props.integrationId,
                props.environment
            )
            .then((res) => res.data),
    refetchOnMount: true,
});

const rows = computed<PermissionAuditHistoryDto[]>(
    () => historyQuery.data.value ?? []
);

/** "Smith, Jane (JSMITH)", or the system when nobody performed it. */
const performer = (row: PermissionAuditHistoryDto): string => {
    const details = row.change_performer_user_details;
    if (!details) {
        return "—";
    }
    const name = [details.first_name, details.last_name]
        .filter(Boolean)
        .join(" ");
    return name
        ? `${name} (${details.username})`
        : (details.username ?? "—");
};

/** The roles a change covered, each with its scopes if it had any. */
const roleLines = (row: PermissionAuditHistoryDto) =>
    (row.privilege_details?.roles ?? []).map((role) => ({
        role: role.role,
        scopes: (role.scopes ?? [])
            .map((scope) => scope.client_id ?? scope.client_name)
            .filter(Boolean)
            .join(", "),
    }));
</script>

<template>
    <TableSkeleton
        class-name="user-permission-table"
        :headers="headers"
        :row-amount="5"
        v-if="historyQuery.isFetching.value"
    />

    <ErrorText
        v-else-if="historyQuery.isError.value"
        error-msg="Failed to fetch the permission history. Please try again."
    />

    <DataTable
                :pt="TABLE_DATATABLE_PT"
        v-else
        class="user-permission-table fam-table"
        :value="rows"
        striped-rows
    >
        <template #empty>No User Permissions History found.</template>

        <Column field="change_date" :header="headers[0]">
            <template #body="{ data }">
                {{ utcToLocalDateTime(data.change_date) }}
            </template>
        </Column>

        <Column
            class="privilege-type-description-col"
            field="privilege_change_type_description"
            :header="headers[1]"
        />

        <Column field="privilege_details" :header="headers[2]">
            <template #body="{ data }">
                <div
                    v-for="line in roleLines(data)"
                    :key="line.role"
                    class="permission-details-col-container"
                >
                    <div class="permission-type-container">
                        <p>Role:</p>
                        <Chip :label="line.role" />
                    </div>
                    <p v-if="line.scopes" class="scopes">{{ line.scopes }}</p>
                </div>
            </template>
        </Column>

        <Column field="change_performer_user_details" :header="headers[3]">
            <template #body="{ data }">{{ performer(data) }}</template>
        </Column>
    </DataTable>
</template>

<style lang="scss">
@use "@/passthrough/datatable/datatablePassThrough.scss";
.user-permission-table {
    .permission-details-col-container {
        display: flex;
        flex-direction: column;
        gap: 0.25rem;
        margin-bottom: 0.5rem;

        &:last-child {
            margin-bottom: 0;
        }
    }

    .permission-type-container {
        display: flex;
        flex-direction: row;
        align-items: center;
        gap: 0.5rem;

        p {
            margin: 0;
        }
    }

    .scopes {
        margin: 0;
        color: var(--semantic-color-text-secondary);
        font-size: 0.875rem;
    }
}
</style>
