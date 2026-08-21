<script setup lang="ts">
import ErrorText from "@/components/UI/ErrorText.vue";
import Button from "@/components/UI/Button.vue";
import PageTitle from "@/components/UI/PageTitle.vue";
import StepContainer from "@/components/UI/StepContainer.vue";
import { ManagePermissionsRoute } from "@/router/routes";
import { AdminMgmtApiService } from "@/services/ApiServiceFactory";
import { selectedApp } from "@/store/ApplicationState";
import { useMutation, useQueryClient } from "@tanstack/vue-query";
import type { CssBulkGrantRowDto } from "fam-api";
import Column from "primevue/column";
import DataTable from "primevue/datatable";
import { computed, ref } from "vue";
import { useRouter } from "vue-router";
import DownloadIcon from "@carbon/icons-vue/es/download/16";
import {
    describeUploadError,
    downloadTemplateCsv,
    fullName,
    EXAMPLE_CSV,
} from "./utils";

/**
 * Grant one role to many users from a CSV.
 *
 * Two steps, deliberately. The upload is checked and shown back as **names and
 * role names** before anything is granted - a table of GUIDs and role codes is
 * not something anybody can check by eye, so confirming one would be theatre.
 *
 * Only ordinary application roles. Administrative roles are refused by the
 * backend: appointing an administrator is not granting access, and doing it by
 * upload would route around the tier rules the administrator screens apply.
 */
const props = defineProps<{
    integrationId: number;
    environment: string;
}>();

const router = useRouter();
const queryClient = useQueryClient();

const fileName = ref<string | null>(null);
const csv = ref<string | null>(null);
const uploadError = ref<string | null>(null);
/** Set once granting has run; the table then shows outcomes, not a preview. */
const applied = ref(false);

const rows = ref<CssBulkGrantRowDto[]>([]);

const validRows = computed(() => rows.value.filter((row) => row.valid));
const errorRows = computed(() => rows.value.filter((row) => !row.valid));

const onFileChange = async (event: Event) => {
    const file = (event.target as HTMLInputElement).files?.[0];
    if (!file) {
        return;
    }
    uploadError.value = null;
    applied.value = false;
    rows.value = [];
    fileName.value = file.name;
    csv.value = await file.text();

    previewMutation.mutate();
};

const previewMutation = useMutation({
    mutationFn: () =>
        AdminMgmtApiService.cssIntegrationsApi
            .previewCssBulkGrants(
                props.integrationId,
                props.environment,
                csv.value ?? ""
            )
            .then((res) => res.data),
    onSuccess: (preview) => {
        rows.value = preview.rows;
    },
    onError: (error: unknown) => {
        uploadError.value = describeUploadError(error);
    },
});

const applyMutation = useMutation({
    mutationFn: () =>
        AdminMgmtApiService.cssIntegrationsApi
            .createCssBulkGrants(
                props.integrationId,
                props.environment,
                csv.value ?? ""
            )
            .then((res) => res.data),
    onSuccess: (outcomes) => {
        rows.value = outcomes;
        applied.value = true;
        // The permissions table is now stale.
        queryClient.invalidateQueries({
            queryKey: [
                "css-user-role-assignments",
                props.integrationId,
                props.environment,
            ],
        });
    },
    onError: (error: unknown) => {
        uploadError.value = describeUploadError(error);
    },
});

const done = () => router.push({ name: ManagePermissionsRoute.name });

const applicationName = computed(
    () => selectedApp.value?.description ?? props.environment.toUpperCase()
);
</script>

<template>
    <div class="bulk-grant-container">
        <PageTitle
            title="Bulk upload permissions"
            :subtitle="`Grant roles to many users in ${applicationName}`"
        />

        <StepContainer title="Choose a file" divider>
            <p class="step-note">
                A CSV with two columns: the user's GUID and the role code. A
                header row is optional.
            </p>
            <pre class="example">{{ EXAMPLE_CSV }}</pre>

            <div class="template-download">
                <Button
                    outlined
                    label="Download template&nbsp;&nbsp;"
                    :icon="DownloadIcon"
                    aria-label="Download a template CSV"
                    @click="downloadTemplateCsv"
                />
            </div>

            <input
                type="file"
                accept=".csv,text/csv"
                aria-label="CSV file"
                @change="onFileChange"
            />

            <p v-if="fileName" class="file-name">{{ fileName }}</p>

            <ErrorText v-if="uploadError" show-icon :error-msg="uploadError" />
        </StepContainer>

        <StepContainer v-if="rows.length" :title="applied ? 'Result' : 'Confirm'">
            <p class="step-note">
                <template v-if="applied">
                    {{ validRows.length }} of {{ rows.length }} row(s) granted.
                </template>
                <template v-else>
                    {{ validRows.length }} row(s) will be granted.
                    <template v-if="errorRows.length">
                        {{ errorRows.length }} row(s) cannot be and will be
                        skipped - each says why below.
                    </template>
                </template>
            </p>

            <DataTable class="fam-table" :value="rows">
                <Column header="Line" field="line_number" />

                <!--
                    The name, not the GUID. Checking a GUID by eye is what this
                    screen exists to avoid.
                -->
                <Column header="User">
                    <template #body="{ data }">
                        <span v-if="fullName(data)">{{ fullName(data) }}</span>
                        <span v-else class="unresolved">{{ data.user_guid }}</span>
                    </template>
                </Column>

                <Column header="Username">
                    <template #body="{ data }">
                        {{ data.user_name ?? "—" }}
                    </template>
                </Column>

                <Column header="Domain">
                    <template #body="{ data }">
                        {{ data.user_type ?? "—" }}
                    </template>
                </Column>

                <Column header="Organization">
                    <template #body="{ data }">
                        {{ data.organization ?? "—" }}
                    </template>
                </Column>

                <!-- The role's name, falling back to the code from the file. -->
                <Column header="Role">
                    <template #body="{ data }">
                        {{ data.role_display_name ?? data.role_code }}
                    </template>
                </Column>

                <Column :header="applied ? 'Outcome' : 'Status'">
                    <template #body="{ data }">
                        <span v-if="data.valid" class="row-ok">
                            {{ applied ? "Granted" : "Ready" }}
                        </span>
                        <span v-else class="row-error">{{ data.error }}</span>
                    </template>
                </Column>
            </DataTable>

            <div class="form-actions">
                <template v-if="!applied">
                    <Button label="Cancel" severity="secondary" @click="done" />
                    <Button
                        :label="`Grant ${validRows.length} permission(s)`"
                        :disabled="
                            validRows.length === 0 ||
                            applyMutation.isPending.value
                        "
                        @click="applyMutation.mutate()"
                    />
                </template>
                <Button v-else label="Done" @click="done" />
            </div>
        </StepContainer>
    </div>
</template>

<style lang="scss">
.bulk-grant-container {
    padding-bottom: 2.5rem;

    .step-note {
        margin-bottom: 1rem;
        max-width: 46rem;
        color: var(--semantic-color-text-secondary);
    }

    .example {
        display: inline-block;
        margin-bottom: 1.5rem;
        padding: 0.75rem 1rem;
        background: var(--semantic-color-surface-layer-2);
        border: 0.0625rem solid var(--semantic-color-border-subtle);
        border-radius: 0.25rem;
    }

    .template-download {
        margin-bottom: 1.5rem;
    }

    .file-name {
        margin-top: 0.75rem;
        color: var(--semantic-color-text-secondary);
    }

    .form-actions {
        display: flex;
        justify-content: flex-end;
        gap: 1rem;
        margin-top: 2rem;
    }

    .row-error,
    .unresolved {
        color: var(--semantic-color-text-error);
    }
}
</style>
