<script setup lang="ts">
import ErrorText from "@/components/UI/ErrorText.vue";
import Button from "@/components/UI/Button.vue";
import PageTitle from "@/components/UI/PageTitle.vue";
import StepContainer from "@/components/UI/StepContainer.vue";
import { ManagePermissionsRoute } from "@/router/routes";
import { AdminMgmtApiService } from "@/services/ApiServiceFactory";
import { invalidateAfterAccessChange } from "@/utils/QueryInvalidation";
import { selectedApp } from "@/store/ApplicationState";
import { useMutation, useQueryClient } from "@tanstack/vue-query";
import type { CssBulkGrantRowDto } from "fam-api";
import Column from "primevue/column";
import DataTable from "primevue/datatable";
import { computed, ref } from "vue";
import { useRouter } from "vue-router";
import DocumentIcon from "@carbon/icons-vue/es/document/16";
import TrashCanIcon from "@carbon/icons-vue/es/trash-can/16";
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
const fileInput = ref<HTMLInputElement | null>(null);
const dragOver = ref(false);
const csv = ref<string | null>(null);
const uploadError = ref<string | null>(null);
/** Set once granting has run; the table then shows outcomes, not a preview. */
const applied = ref(false);

const rows = ref<CssBulkGrantRowDto[]>([]);

const validRows = computed(() => rows.value.filter((row) => row.valid));
const errorRows = computed(() => rows.value.filter((row) => !row.valid));

/**
 * Take a chosen file, however it arrived.
 *
 * The drop zone, the click-to-browse dialog and the hidden input all land here,
 * so a file dropped on the zone behaves exactly like one picked from the dialog
 * - including re-running the preview.
 */
const acceptFile = async (file: File) => {
    uploadError.value = null;
    applied.value = false;
    rows.value = [];
    fileName.value = file.name;
    csv.value = await file.text();

    previewMutation.mutate();
};

const onFileChange = async (event: Event) => {
    const file = (event.target as HTMLInputElement).files?.[0];
    if (file) {
        await acceptFile(file);
    }
};

const openFileDialog = () => fileInput.value?.click();

const onDrop = async (event: DragEvent) => {
    dragOver.value = false;
    const file = event.dataTransfer?.files?.[0];
    if (file) {
        await acceptFile(file);
    }
};

/** Clearing the input's value matters: without it, re-picking the same file fires no change event. */
const removeFile = () => {
    fileName.value = null;
    csv.value = null;
    rows.value = [];
    applied.value = false;
    uploadError.value = null;
    if (fileInput.value) {
        fileInput.value.value = "";
    }
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
        invalidateAfterAccessChange(
            queryClient, props.integrationId, props.environment
        );
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

        <StepContainer class="template-step" title="Template" divider>
            <!--
                A button, not an anchor: this builds a blob and hands it to the
                browser, so there is no href to follow and nothing to open in a
                new tab. Styled as a link because that is what it behaves like
                to the reader.
            -->
            <p class="step-note">
                A CSV with two columns: the user's GUID and the role code.
                <button
                    type="button"
                    class="template-link"
                    @click="downloadTemplateCsv"
                >
                    Download the template</button
                >.
            </p>
            <pre class="example">{{ EXAMPLE_CSV }}</pre>

            <!--
                Hidden, but still the real input: it backs both the drop zone's
                click and the OS dialog, and it is what assistive technology and
                tests drive. Visually replaced, not removed.
            -->
            <input
                ref="fileInput"
                type="file"
                class="visually-hidden-input"
                accept=".csv,text/csv"
                aria-label="CSV file"
                @change="onFileChange"
            />

        </StepContainer>
        <StepContainer class="choose-file-step" title="Choose a file" divider>
            <div
                class="dnd-zone"
                :class="{ 'dnd-zone--drag': dragOver }"
                role="button"
                tabindex="0"
                aria-label="Drag and drop a CSV here, or click to choose one"
                @click="openFileDialog"
                @keydown.enter.prevent="openFileDialog"
                @keydown.space.prevent="openFileDialog"
                @dragover.prevent="dragOver = true"
                @dragleave="dragOver = false"
                @drop.prevent="onDrop"
            >
                <span class="dnd-zone__label">
                    Drag and drop a file here or click to upload
                </span>
            </div>

            <div v-if="fileName" class="file-chip">
                <span class="file-chip__icon">
                    <component :is="DocumentIcon" />
                </span>
                <span class="file-chip__name">{{ fileName }}</span>
                <button
                    type="button"
                    class="file-chip__remove"
                    :aria-label="`Remove ${fileName}`"
                    @click="removeFile"
                >
                    <component :is="TrashCanIcon" />
                </button>
            </div>

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

                <!--
                    The scope, resolved. A district code and a client number are
                    not things anybody can check by eye, which is the whole
                    reason this confirmation step exists - so the name is shown
                    and the code kept beside it.
                -->
                <Column header="Scope">
                    <template #body="{ data }">
                        <span
                            v-if="!data.district && !data.forest_client_number"
                            class="no-scope"
                            >Whole application</span
                        >
                        <span v-else class="scope-cell">
                            <span v-if="data.district">
                                {{ data.district_name ?? data.district }}
                                <span class="scope-code">({{ data.district }})</span>
                            </span>
                            <span v-if="data.forest_client_number">
                                {{
                                    data.forest_client_name ??
                                    data.forest_client_number
                                }}
                                <span class="scope-code"
                                    >({{ data.forest_client_number }})</span
                                >
                            </span>
                        </span>
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

    /*
        Breathing room between the page title and the first step - and only that
        step. This margin used to sit on a class both steps carried, so it was
        also spacing "Choose a file" from the rule above it, where nothing was
        wanted. One class, two jobs, pulling opposite ways.

        Carried on an explicit class rather than :first-of-type, which matches on
        element type: PageTitle renders a bare <div>, so the step container is
        never the first div and the rule silently did nothing.
    */
    .template-step {
        margin-top: 2.5rem;
    }

    /*
        Tighter rules than StepContainer's own 1.25rem.

        Margins do not collapse across these: `.step-container` sets
        `container-type: inline-size`, and containment stops a rule's bottom
        margin escaping its parent to meet the next step's top margin. Every
        margin in this stack therefore adds, which is why the gap above "Choose
        a file" reached 7rem from three rules that each looked modest.
    */
    .step-container > hr.solid.step-divider {
        margin-top: 0.75rem;
        margin-bottom: 0.75rem;
    }

    /*
        The drop zone, ported from FSPTS's XML submission screen so the two
        applications ask for a file the same way. Tokens are FAM's; the shape,
        the dashed border and the drag highlight are theirs.

        The native input is hidden rather than removed: it is what the dialog,
        assistive technology and the tests actually drive.
    */
    .visually-hidden-input {
        position: absolute;
        width: 1px;
        height: 1px;
        padding: 0;
        margin: -1px;
        overflow: hidden;
        clip: rect(0, 0, 0, 0);
        white-space: nowrap;
        border: 0;
    }

    .dnd-zone {
        display: flex;
        align-items: center;
        min-height: 5rem;
        max-width: 32rem;
        padding: 1rem;
        border: 0.0625rem dashed var(--semantic-color-border-subtle);
        border-radius: 0.25rem;
        cursor: pointer;

        &:focus-visible {
            outline: 0.125rem solid var(--semantic-color-focus-default);
            outline-offset: 0.125rem;
        }
    }

    /* Also applied on dragover, which is why it is not merely a :hover. */
    .dnd-zone--drag,
    .dnd-zone:hover {
        border-color: var(--semantic-color-link-primary);
        background: var(--semantic-color-surface-layer-2);
    }

    .dnd-zone__label {
        color: var(--semantic-color-link-primary);
    }

    .file-chip {
        display: flex;
        align-items: center;
        gap: 0.5rem;
        max-width: 32rem;
        margin-top: 0.75rem;
        padding: 0.5rem 0.75rem;
        border: 0.0625rem solid var(--semantic-color-border-subtle);
        border-radius: 0.125rem;

        .file-chip__name {
            flex: 1;
        }

        .file-chip__icon,
        .file-chip__remove {
            display: flex;
            align-items: center;

            svg {
                fill: var(--semantic-color-text-secondary);
            }
        }

        .file-chip__remove {
            background: none;
            border: none;
            padding: 0.25rem;
            cursor: pointer;

            &:hover svg {
                fill: var(--semantic-color-text-error);
            }
        }
    }

    .step-note {
        margin-bottom: 1rem;
        max-width: 46rem;
        color: var(--semantic-color-text-secondary);
    }

    .scope-cell {
        display: flex;
        flex-direction: column;
        gap: 0.125rem;
    }

    .scope-code,
    .no-scope {
        color: var(--semantic-color-text-secondary);
    }

    .example {
        display: inline-block;
        /*
            Enough to lift the sample off the rule below it, no more. Its margin
            adds to the rule's rather than collapsing with it - see the rule
            above - so anything larger reads as a gap of its own.
        */
        margin-bottom: 0.5rem;
        padding: 0.75rem 1rem;
        background: var(--semantic-color-surface-layer-2);
        border: 0.0625rem solid var(--semantic-color-border-subtle);
        border-radius: 0.25rem;
    }

    /*
        Follows the toggle-link pattern in NotificationContent: a real button
        stripped back to look like the sentence it sits in.
    */
    .template-link {
        background: none;
        border: none;
        padding: 0;
        font: inherit;
        color: var(--semantic-color-link-primary);
        text-decoration: underline;
        cursor: pointer;

        &:hover {
            color: var(--semantic-color-link-primary-hover);
        }
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
