<script setup lang="ts">
import ErrorText from "@/components/UI/ErrorText.vue";
import { TABLE_DATATABLE_PT } from "@/passthrough/datatable/datatablePassThrough";
import PageTitle from "@/components/UI/PageTitle.vue";
import StepContainer from "@/components/UI/StepContainer.vue";
import {
    fetchSelfApplicationRoles,
    fetchSelfPermissions,
} from "@/services/AuthApiService";
import { useQuery } from "@tanstack/vue-query";
import type { SelfApplicationRoleDto, SelfPermissionDto } from "fam-api";
import Column from "primevue/column";
import DataTable from "primevue/datatable";
import { computed } from "vue";

/**
 * What the signed-in user holds, in two parts.
 *
 * They come from different places and at different speeds, which is why they are
 * two queries rather than one:
 *
 * - what they may **administer** is on their token, so it is immediate;
 * - what they hold **as a user** of each application lives in CSS, one request
 *   per integration and environment, so it is slow enough to notice.
 *
 * Fetching them together would make the fast half wait for the slow one.
 */
const permissionsQuery = useQuery({
    queryKey: ["self-permissions"],
    queryFn: fetchSelfPermissions,
    // Roles change through this very application, so a stale list is confusing.
    refetchOnMount: true,
});

const applicationRolesQuery = useQuery({
    queryKey: ["self-application-roles"],
    queryFn: fetchSelfApplicationRoles,
    refetchOnMount: true,
});

const permissions = computed<SelfPermissionDto[]>(
    () => permissionsQuery.data.value ?? []
);

const applicationRoles = computed<SelfApplicationRoleDto[]>(
    () => applicationRolesQuery.data.value ?? []
);

/** "DCC" or "00001018", or an em dash when the role is not scoped. */
const scopeOf = (role: SelfApplicationRoleDto): string =>
    role.scope_value ? role.scope_value : "—";
</script>

<template>
    <div class="my-permissions-container">
        <PageTitle
            title="My permissions"
            subtitle="The applications you can use, and what you can administer"
        />

        <StepContainer title="Application roles" divider class="application-roles">
            <p class="section-note">
                The roles you hold as a user of each application. Only
                applications FAM administers are listed - a role in an
                application managed by another team is not visible here.
            </p>

            <ErrorText
                v-if="applicationRolesQuery.isError.value"
                show-icon
                error-msg="Your application roles could not be loaded. Please try again."
            />

            <DataTable
                :pt="TABLE_DATATABLE_PT"
                class="fam-table"
                :value="applicationRoles"
            >
                <template #empty>
                    {{
                        applicationRolesQuery.isLoading.value
                            ? "Checking every application…"
                            : "You hold no roles in any application"
                    }}
                </template>

                <Column header="Application" field="application_name" />

                <Column header="Environment">
                    <template #body="{ data }">
                        {{ data.environment.toUpperCase() }}
                    </template>
                </Column>

                <Column header="Role">
                    <template #body="{ data }">
                        {{ data.role_display_name ?? data.base_role_name }}
                    </template>
                </Column>

                <Column header="Description">
                    <template #body="{ data }">
                        <span v-if="data.role_description">
                            {{ data.role_description }}
                        </span>
                        <span v-else class="not-applicable">—</span>
                    </template>
                </Column>

                <!--
                    A scoped role is one CSS role per district or client, so
                    somebody granted three districts holds three roles and gets
                    three rows - which is what they actually have.
                -->
                <Column header="District / client">
                    <template #body="{ data }">
                        <span v-if="data.scope_value">{{ scopeOf(data) }}</span>
                        <span v-else class="not-applicable">—</span>
                    </template>
                </Column>
            </DataTable>
        </StepContainer>

        <StepContainer title="Administrative permissions">
            <p class="section-note">
                What you can administer in FAM.
            </p>

            <ErrorText
                v-if="permissionsQuery.isError.value"
                show-icon
                error-msg="Your permissions could not be loaded. Please try again."
            />

            <DataTable class="fam-table" :value="permissions">
                <template #empty>
                    {{
                        permissionsQuery.isLoading.value
                            ? "Loading your permissions…"
                            : "You do not administer any applications"
                    }}
                </template>

                <Column header="Application" field="application_name" />

                <Column header="Environment">
                    <template #body="{ data }">
                        <!--
                            FAM_ADMIN names no application and no environment,
                            because it administers every one.
                        -->
                        <span v-if="data.environment">
                            {{ data.environment.toUpperCase() }}
                        </span>
                        <span v-else class="not-applicable">—</span>
                    </template>
                </Column>

                <Column header="Role" field="role_description" />
            </DataTable>
        </StepContainer>
    </div>
</template>

<style lang="scss">
@use "@/passthrough/datatable/datatablePassThrough.scss";
.my-permissions-container {
    padding-bottom: 2.5rem;

    /*
        Separates the first section from the page title, 34px to 58px.

        padding, not margin: the heading's own 20px top margin sits inside the
        step container with nothing between, so a margin-top here would collapse
        with it and add almost nothing.
    */
    .application-roles {
        padding-top: 1.5rem;
    }

    .section-note {
        margin-bottom: 1.5rem;
        max-width: 46rem;
        color: var(--semantic-color-text-secondary);
    }

    .not-applicable {
        color: var(--semantic-color-text-secondary);
    }
}
</style>
