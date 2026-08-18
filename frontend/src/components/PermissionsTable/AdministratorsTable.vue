<script setup lang="ts">
import ErrorText from "@/components/UI/ErrorText.vue";
import { AdminMgmtApiService } from "@/services/ApiServiceFactory";
import { useQuery } from "@tanstack/vue-query";
import type { AdminRoleAuthGroup, CssAdministratorRowDto } from "fam-api";
import Column from "primevue/column";
import DataTable from "primevue/datatable";
import { computed } from "vue";

/**
 * Who administers one application, at one tier.
 *
 * Read-only, unlike the users table. Appointing and removing administrators is
 * the grant path's job and is guarded per tier; this only answers "who else
 * administers this".
 *
 * The rows come from **FAM's own CSS integration**, not the application's - an
 * administrator holds `APP_ADMIN_<id>_<ENV>` there rather than any role on the
 * application itself. That is why they never appear on the Users tab, and why
 * this is a separate read rather than a filter over the same list.
 */
const props = defineProps<{
    integrationId: number;
    environment: string;
    tier: AdminRoleAuthGroup;
    appName: string;
}>();

const administratorsQuery = useQuery({
    queryKey: computed(() => [
        "css-administrators",
        props.integrationId,
        props.environment,
        props.tier,
    ]),
    queryFn: () =>
        AdminMgmtApiService.cssIntegrationsApi
            .getCssApplicationAdministrators(
                props.integrationId,
                props.environment,
                props.tier
            )
            .then((res) => res.data),
    refetchOnMount: true,
});

const rows = computed<CssAdministratorRowDto[]>(
    () => administratorsQuery.data.value ?? []
);

/**
 * The backend's own message when there is one.
 *
 * A generic line here hid the actual reason - a missing
 * `CSS_OWN_INTEGRATION_ID`, or a refusal - behind "please try again", which is
 * advice that would not have helped in either case.
 */
const errorMessage = computed(() => {
    const error = administratorsQuery.error.value as any;
    return (
        error?.response?.data?.description ??
        error?.message ??
        "The administrators could not be loaded. Please try again."
    );
});

const fullName = (row: CssAdministratorRowDto): string =>
    [row.first_name, row.last_name].filter(Boolean).join(" ");
</script>

<template>
    <div class="fam-table administrators-table">
        <ErrorText
            v-if="administratorsQuery.isError.value"
            show-icon
            :error-msg="errorMessage"
        />

        <DataTable class="fam-table" :value="rows">
            <template #empty>
                {{
                    administratorsQuery.isLoading.value
                        ? "Loading administrators…"
                        : `${appName} has no administrators at this level`
                }}
            </template>

            <Column header="User Name" field="username" />

            <Column header="Domain">
                <template #body="{ data }">
                    {{ data.domain ?? "—" }}
                </template>
            </Column>

            <Column header="Full Name">
                <template #body="{ data }">
                    <!--
                        Blank until the person first signs in: CSS holds only a
                        username for somebody who has never logged in.
                    -->
                    {{ fullName(data) || "—" }}
                </template>
            </Column>

            <Column header="Email">
                <template #body="{ data }">
                    {{ data.email ?? "—" }}
                </template>
            </Column>

            <!--
                Only meaningful for a delegated administrator: they are delegated
                one role each, so somebody delegated three roles is three rows.
                An application administrator is delegated nothing in particular.
            -->
            <Column v-if="tier === 'DELEGATED_ADMIN'" header="May grant">
                <template #body="{ data }">
                    {{ data.delegated_role_name ?? "—" }}
                </template>
            </Column>
        </DataTable>
    </div>
</template>
