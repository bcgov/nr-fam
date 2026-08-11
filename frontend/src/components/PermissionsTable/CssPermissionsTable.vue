<script setup lang="ts">
import { AdminMgmtApiService } from "@/services/ApiServiceFactory";
import { useQuery } from "@tanstack/vue-query";
import type { CssUserRoleRowDto } from "fam-api";
import Column from "primevue/column";
import DataTable from "primevue/datatable";
import InputText from "primevue/inputtext";
import { computed, ref } from "vue";

/**
 * Permissions sourced from CSS.
 *
 * A CSS integration is identified by an id and an environment, not by a single
 * FAM application id - one integration spans dev/test/prod - so both are props.
 *
 * Only the columns CSS can actually supply are rendered. There is no granted-on
 * date, no expiry and no organisation in CSS, so those columns are absent rather
 * than present-and-always-blank, which would read as missing data rather than as
 * data that does not exist.
 */
const props = defineProps<{
    integrationId: number;
    environment: string;
}>();

const search = ref("");

const assignmentsQuery = useQuery({
    queryKey: computed(() => [
        "css-user-role-assignments",
        props.integrationId,
        props.environment,
    ]),
    queryFn: () =>
        AdminMgmtApiService.cssIntegrationsApi
            .getCssUserRoleAssignments(props.integrationId, props.environment)
            .then((res) => res.data),
    refetchOnMount: true,
});

const fullName = (row: CssUserRoleRowDto) =>
    [row.first_name, row.last_name].filter(Boolean).join(" ");

/**
 * The role as shown: the base role, with the scope value appended when there is
 * one. The scope only exists in the generated role name, so this is the only
 * place it can come from.
 */
const roleDisplay = (row: CssUserRoleRowDto) =>
    row.scope_value ? `${row.role_name} (${row.scope_value})` : row.role_name;

const rows = computed<CssUserRoleRowDto[]>(
    () => assignmentsQuery.data.value ?? []
);

const filteredRows = computed<CssUserRoleRowDto[]>(() => {
    const term = search.value.trim().toLowerCase();
    if (!term) {
        return rows.value;
    }
    return rows.value.filter((row) =>
        [
            row.username,
            row.email,
            fullName(row),
            roleDisplay(row),
            row.scope_value,
        ]
            .filter(Boolean)
            .some((field) => String(field).toLowerCase().includes(term))
    );
});
</script>

<template>
    <div class="css-permissions-table-container">
        <div class="table-header">
            <span class="row-count">
                {{ filteredRows.length }} of {{ rows.length }}
            </span>
            <InputText
                v-model="search"
                placeholder="Search by name, email or role"
                aria-label="Search permissions"
            />
        </div>

        <DataTable
            class="fam-table"
            :value="filteredRows"
            :loading="assignmentsQuery.isLoading.value"
            paginator
            :rows="20"
            :rows-per-page-options="[20, 50, 100]"
            sort-field="username"
            :sort-order="1"
        >
            <template #empty>
                <span v-if="assignmentsQuery.isError.value">
                    Failed to load permissions from CSS. Please try again.
                </span>
                <span v-else>No permissions found</span>
            </template>

            <Column header="User Name" field="username" sortable />

            <Column header="Domain" field="domain" sortable>
                <template #body="{ data }">
                    {{ data.domain ?? "—" }}
                </template>
            </Column>

            <Column header="Full Name" sortable sort-field="first_name">
                <template #body="{ data }">
                    {{ fullName(data) || "—" }}
                </template>
            </Column>

            <Column header="Email" field="email" sortable>
                <template #body="{ data }">
                    {{ data.email ?? "—" }}
                </template>
            </Column>

            <Column header="Role" sortable sort-field="role_name">
                <template #body="{ data }">
                    {{ roleDisplay(data) }}
                </template>
            </Column>

            <Column header="Scope" sortable sort-field="scope_type">
                <template #body="{ data }">
                    {{ data.scope_type ?? "—" }}
                </template>
            </Column>
        </DataTable>
    </div>
</template>

<style lang="scss">
.css-permissions-table-container {
    .table-header {
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: 1rem;
        margin-bottom: 1rem;
    }

    .row-count {
        color: var(--semantic-color-text-secondary);
    }

    .fam-table {
        .p-datatable-emptymessage {
            background-color: var(--semantic-color-surface-layer-1);
        }
    }
}
</style>
