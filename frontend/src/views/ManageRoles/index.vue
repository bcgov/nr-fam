<script setup lang="ts">
import BoolCheckbox from "@/components/UI/BoolCheckbox.vue";
import Button from "@/components/UI/Button.vue";
import Dropdown from "@/components/UI/Dropdown.vue";
import ErrorText from "@/components/UI/ErrorText.vue";
import HelperText from "@/components/UI/HelperText.vue";
import Label from "@/components/UI/Label.vue";
import PageTitle from "@/components/UI/PageTitle.vue";
import StepContainer from "@/components/UI/StepContainer.vue";
import SubsectionTitle from "@/components/UI/SubsectionTitle.vue";
import { AdminMgmtApiService } from "@/services/ApiServiceFactory";
import { useMutation, useQuery, useQueryClient } from "@tanstack/vue-query";
import TrashIcon from "@carbon/icons-vue/es/trash-can/16";
import type {
    CssApplicationOptionDto,
    CssRoleBulkCreateResultDto,
    CssRoleOptionDto,
} from "fam-api";
import Column from "primevue/column";
import ConfirmDialog from "primevue/confirmdialog";
import DataTable from "primevue/datatable";
import InputText from "primevue/inputtext";
import { useConfirm } from "primevue/useconfirm";
import { computed, ref } from "vue";
import DeleteRoleDialogText from "./DeleteRoleDialogText.vue";
import {
    MAX_DESCRIPTION_LENGTH,
    MAX_ROLE_NAME_LENGTH,
    applyScopeChoice,
    describeScope,
    getDefaultFormData,
    toCreateRequest,
    validateManageRolesForm,
    type ManageRolesFormType,
} from "./utils";

/**
 * Define the roles an application offers.
 *
 * FAM administrators only. Everywhere else in FAM decides who holds a role;
 * this decides which roles exist, which is a change to the application's own
 * authorisation model.
 *
 * A CSS role holds nothing but a name, so a role defined here becomes up to
 * three of them: the role itself named for the code, a scope marker composed
 * into it, and a sidecar carrying the description. That shape lives on the
 * backend - see `CssIntegrationService.createRole`.
 */
const queryClient = useQueryClient();

const selectedApp = ref<CssApplicationOptionDto | null>(null);
const form = ref<ManageRolesFormType>(getDefaultFormData());
const errors = ref<Record<string, string>>({});
const submitError = ref<string | null>(null);
const created = ref<CssRoleOptionDto | null>(null);
/** Set after a successful all-environments creation. */
const createdEverywhere = ref<CssRoleBulkCreateResultDto | null>(null);
/** Set after a successful deletion; describes what actually went. */
const deleted = ref<string | null>(null);

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

/**
 * The roles the chosen application already has.
 *
 * Shown because a code cannot be reused, and finding that out from a rejected
 * submission is a poor way to learn it.
 */
const rolesQuery = useQuery({
    queryKey: computed(() => [
        "css-roles",
        selectedApp.value?.integration_id,
        selectedApp.value?.environment,
    ]),
    queryFn: () =>
        AdminMgmtApiService.cssIntegrationsApi
            .getCssApplicationRoles(
                selectedApp.value!.integration_id,
                selectedApp.value!.environment
            )
            .then((res) => res.data),
    enabled: computed(() => !!selectedApp.value),
});

const existingRoles = computed<CssRoleOptionDto[]>(
    () => rolesQuery.data.value ?? []
);

/**
 * How many people hold each role.
 *
 * Its own query, because the backend needs one upstream request per role to
 * answer it. Keeping it separate means the table renders as soon as the roles
 * arrive and fills the counts in when they do, rather than waiting on the
 * slower call - and the grant screen, which shares the `css-roles` query, never
 * pays for counts it does not show.
 */
const memberCountsQuery = useQuery({
    queryKey: computed(() => [
        "css-role-member-counts",
        selectedApp.value?.integration_id,
        selectedApp.value?.environment,
    ]),
    queryFn: () =>
        AdminMgmtApiService.cssIntegrationsApi
            .getCssApplicationRoleMemberCounts(
                selectedApp.value!.integration_id,
                selectedApp.value!.environment
            )
            .then((res) => res.data),
    enabled: computed(() => !!selectedApp.value),
});

/** Role name to member count. Absent while loading, which is not zero. */
const memberCounts = computed<Record<string, number>>(() =>
    Object.fromEntries(
        (memberCountsQuery.data.value ?? []).map((entry) => [
            entry.role_name,
            entry.member_count,
        ])
    )
);

/**
 * A role nobody holds still gets a 0 rather than a blank, but only once the
 * counts have loaded: the backend omits roles with no members, so an absent
 * entry after a successful load genuinely means none.
 */
const memberCountFor = (role: CssRoleOptionDto): number | null =>
    memberCountsQuery.isSuccess.value
        ? (memberCounts.value[role.name] ?? 0)
        : null;

const handleApplicationChange = (event: { value: CssApplicationOptionDto }) => {
    selectedApp.value = event.value;
    created.value = null;
    createdEverywhere.value = null;
    deleted.value = null;
    submitError.value = null;
};

/** Ticking one scope clears the other - a role is scoped one way or not at all. */
const setScope = (
    field: "requiresDistrict" | "requiresForestClient",
    checked: boolean
) => {
    form.value = applyScopeChoice(form.value, field, checked);
};

const createMutation = useMutation({
    mutationFn: () =>
        AdminMgmtApiService.cssIntegrationsApi
            .createCssApplicationRole(
                selectedApp.value!.integration_id,
                selectedApp.value!.environment,
                toCreateRequest(form.value)
            )
            .then((res) => res.data),
    onSuccess: (role) => {
        created.value = role;
        form.value = getDefaultFormData();
        // So the role shows up here and on the grant screen without a reload.
        queryClient.invalidateQueries({
            queryKey: [
                "css-roles",
                selectedApp.value?.integration_id,
                selectedApp.value?.environment,
            ],
        });
    },
    onError: (error: any) => {
        // The backend's message names the actual problem - a taken code, a
        // malformed one - so it is worth more than a generic failure line.
        submitError.value =
            error?.response?.data?.description ??
            error?.message ??
            "The role could not be created.";
    },
});

/**
 * Defines the role in every environment the application has.
 *
 * Sends no environment: the endpoint uses the integration's own environment
 * list, so an application with only dev and test gets two rather than a
 * request for a prod that does not exist. It refuses outright if the code is
 * taken in any environment, so this either creates it everywhere or nowhere.
 */
const createAllMutation = useMutation({
    mutationFn: () =>
        AdminMgmtApiService.cssIntegrationsApi
            .createCssApplicationRoleAllEnvironments(
                selectedApp.value!.integration_id,
                toCreateRequest(form.value)
            )
            .then((res) => res.data),
    onSuccess: (result) => {
        createdEverywhere.value = result;
        form.value = getDefaultFormData();
        // Only the selected environment's listings are on screen, but the role
        // now exists in the others too.
        for (const key of ["css-roles", "css-role-member-counts"]) {
            queryClient.invalidateQueries({
                queryKey: [
                    key,
                    selectedApp.value?.integration_id,
                    selectedApp.value?.environment,
                ],
            });
        }
    },
    onError: (error: any) => {
        // Names the environments that already have the code, which is the whole
        // reason the request was refused.
        submitError.value =
            error?.response?.data?.description ??
            error?.message ??
            "The role could not be created in every environment.";
    },
});

/** Wording for the delete confirmation, set just before the dialog opens. */
const confirmTextProps = ref<{
    roleName: string;
    description?: string | null;
    appName: string;
    memberCount: number | null;
} | null>(null);

const confirm = useConfirm();

const deleteMutation = useMutation({
    mutationFn: (role: CssRoleOptionDto) =>
        AdminMgmtApiService.cssIntegrationsApi
            .deleteCssApplicationRole(
                selectedApp.value!.integration_id,
                selectedApp.value!.environment,
                role.name
            )
            .then((res) => res.data),
    onSuccess: (result) => {
        submitError.value = null;
        created.value = null;
        // Says what actually went: one role on screen can be several in CSS.
        const extra = result.removed_roles.length - 1;
        const delegations = result.removed_delegations.length;
        deleted.value =
            `Deleted ${result.role_name}` +
            (extra > 0 ? ` and ${extra} role(s) derived from it` : "") +
            (result.members_affected > 0
                ? `. ${result.members_affected} user(s) lost that access`
                : "") +
            // Withdrawn with the role: a delegation naming a role that no longer
            // exists would still let its holder recreate it by granting it.
            (delegations > 0
                ? `. ${delegations} delegated admin privilege(s) withdrawn`
                : "") +
            ".";

        // Both listings are now stale, and so is the grant screen's picker.
        for (const key of ["css-roles", "css-role-member-counts"]) {
            queryClient.invalidateQueries({
                queryKey: [
                    key,
                    selectedApp.value?.integration_id,
                    selectedApp.value?.environment,
                ],
            });
        }
    },
    onError: (error: any) => {
        deleted.value = null;
        // The backend names what it managed to remove before failing, which
        // matters here: a deletion cannot be rolled back.
        submitError.value =
            error?.response?.data?.description ??
            error?.message ??
            "The role could not be deleted.";
    },
});

const confirmDelete = (role: CssRoleOptionDto) => {
    confirmTextProps.value = {
        roleName: role.name,
        description: role.display_name,
        appName: selectedApp.value?.description ?? "this application",
        memberCount: memberCountFor(role),
    };

    confirm.require({
        group: "deleteRole",
        header: "Delete role",
        acceptLabel: "Delete",
        rejectLabel: "Cancel",
        acceptProps: { severity: "danger" },
        rejectProps: { severity: "secondary", outlined: true },
        accept: () => deleteMutation.mutate(role),
    });
};

/**
 * Validates the form and clears any previous notice.
 *
 * Shared by both buttons so they cannot disagree about what a valid role is.
 * The duplicate-code check is not here - that is the backend's, because only it
 * can see what already exists, and in the all-environments case it has to look
 * in every environment before writing to any.
 */
const validateForm = async (): Promise<boolean> => {
    submitError.value = null;
    created.value = null;
    createdEverywhere.value = null;
    deleted.value = null;
    errors.value = {};

    try {
        await validateManageRolesForm().validate(form.value, {
            abortEarly: false,
        });
        return true;
    } catch (validationError: any) {
        for (const item of validationError.inner ?? []) {
            if (item.path && !errors.value[item.path]) {
                errors.value[item.path] = item.message;
            }
        }
        return false;
    }
};

const onSubmit = async () => {
    if (await validateForm()) {
        createMutation.mutate();
    }
};

const onSubmitAllEnvironments = async () => {
    if (await validateForm()) {
        createAllMutation.mutate();
    }
};
</script>

<template>
    <div class="manage-roles-container">
        <PageTitle
            title="Manage roles"
            subtitle="Define the roles an application offers"
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
                placeholder="Choose an application to add a role to"
                :is-fetching="applicationsQuery.isLoading.value"
                :is-error="applicationsQuery.isError.value"
                error-msg="Failed to load applications from CSS. Please try again."
            />
        </div>

        <template v-if="selectedApp">
            <StepContainer title="Create a role" divider>
                <div class="role-form">
                    <div class="field">
                        <Label
                            label-text="Role code"
                            for="roleCode"
                            required
                        />
                        <InputText
                            id="roleCode"
                            class="w-100 custom-height"
                            v-model="form.roleCode"
                            placeholder="FREP_ADMINISTRATOR"
                            :invalid="!!errors.roleCode"
                        />
                        <HelperText
                            :text="
                                errors.roleCode ||
                                'The value applications check for. Letters, digits and underscores.'
                            "
                            :is-error="!!errors.roleCode"
                        />
                    </div>

                    <div class="field">
                        <Label label-text="Role name" for="roleName" required />
                        <InputText
                            id="roleName"
                            class="w-100 custom-height"
                            v-model="form.roleName"
                            placeholder="View All"
                            :maxlength="MAX_ROLE_NAME_LENGTH"
                            :invalid="!!errors.roleName"
                        />
                        <HelperText
                            :text="
                                errors.roleName ||
                                'The short name shown on pickers and permission pills.'
                            "
                            :is-error="!!errors.roleName"
                        />
                    </div>

                    <div class="field">
                        <Label label-text="Description" for="description" />
                        <InputText
                            id="description"
                            class="w-100 custom-height"
                            v-model="form.description"
                            placeholder="Allows users to view all the FSPs but not edit"
                            :maxlength="MAX_DESCRIPTION_LENGTH"
                            :invalid="!!errors.description"
                        />
                        <HelperText
                            :text="
                                errors.description ||
                                'Optional. A sentence explaining what the role allows.'
                            "
                            :is-error="!!errors.description"
                        />
                    </div>

                    <div class="field">
                        <SubsectionTitle title="Scope" />
                        <BoolCheckbox
                            id="requiresDistrict"
                            label="Requires a district selection"
                            :model-value="form.requiresDistrict"
                            @update:model-value="
                                (value: any) =>
                                    setScope('requiresDistrict', !!value)
                            "
                        />
                        <BoolCheckbox
                            id="requiresForestClient"
                            label="Requires a forest client selection"
                            :model-value="form.requiresForestClient"
                            @update:model-value="
                                (value: any) =>
                                    setScope('requiresForestClient', !!value)
                            "
                        />
                        <HelperText
                            v-if="describeScope(form)"
                            :text="describeScope(form)"
                        />
                    </div>
                </div>

                <div class="form-actions">
                    <Button
                        label="Create role"
                        @click="onSubmit"
                        :disabled="
                            createMutation.isPending.value ||
                            createAllMutation.isPending.value
                        "
                    />
                    <!--
                        Secondary: creating in the selected environment is the
                        ordinary action, and this one writes to environments the
                        screen is not showing.
                    -->
                    <Button
                        label="Create in all environments"
                        severity="secondary"
                        :is-loading="createAllMutation.isPending.value"
                        @click="onSubmitAllEnvironments"
                        :disabled="
                            createMutation.isPending.value ||
                            createAllMutation.isPending.value
                        "
                    />
                </div>

                <ErrorText v-if="submitError" show-icon :error-msg="submitError" />

                <p v-if="created" class="created-message">
                    Created <strong>{{ created.name }}</strong>
                    ({{ created.display_name }}). It can be granted from Manage
                    permissions now.
                </p>

                <p v-if="createdEverywhere" class="created-message">
                    Created
                    <strong>{{ createdEverywhere.role_code }}</strong>
                    ({{ createdEverywhere.description }}) in
                    <strong>
                        {{ createdEverywhere.environments.join(", ") }}
                    </strong>
                    . Only {{ selectedApp?.environment }} is listed below.
                </p>

                <p v-if="deleted" class="created-message">{{ deleted }}</p>
            </StepContainer>

            <StepContainer title="Existing roles" class="existing-roles">
                <!--
                    Always mounted, with only the wording conditional. Mounting
                    the dialog at the moment it is asked to open would risk it
                    missing the request that opened it.
                -->
                <ConfirmDialog group="deleteRole">
                    <template #message>
                        <DeleteRoleDialogText
                            v-if="confirmTextProps"
                            :role-name="confirmTextProps.roleName"
                            :description="confirmTextProps.description"
                            :app-name="confirmTextProps.appName"
                            :member-count="confirmTextProps.memberCount"
                        />
                    </template>
                </ConfirmDialog>

                <DataTable class="fam-table" :value="existingRoles">
                    <template #empty>
                        {{
                            rolesQuery.isLoading.value
                                ? "Loading roles…"
                                : "This application has no roles yet"
                        }}
                    </template>

                    <Column header="Role code" field="name" />

                    <Column header="Name">
                        <template #body="{ data }">
                            {{ data.display_name ?? "—" }}
                        </template>
                    </Column>

                    <Column header="Description">
                        <template #body="{ data }">
                            {{ data.description ?? "—" }}
                        </template>
                    </Column>

                    <Column header="Scope">
                        <template #body="{ data }">
                            <span v-if="data.role_type_district">District</span>
                            <span v-else-if="data.role_type_client">
                                Forest client
                            </span>
                            <span v-else>None</span>
                        </template>
                    </Column>

                    <!--
                        Counts people, not grants: someone holding a scoped role
                        for three districts is one member. An em dash rather
                        than 0 while the counts are still loading, so an unknown
                        never reads as "nobody".
                    -->
                    <Column header="Members">
                        <template #body="{ data }">
                            <span v-if="memberCountFor(data) !== null">
                                {{ memberCountFor(data) }}
                            </span>
                            <span v-else class="count-unknown">—</span>
                        </template>
                    </Column>

                    <Column header="Action">
                        <template #body="{ data }">
                            <button
                                title="Delete role"
                                class="btn btn-icon"
                                type="button"
                                :disabled="deleteMutation.isPending.value"
                                @click="confirmDelete(data)"
                            >
                                <TrashIcon />
                            </button>
                        </template>
                    </Column>
                </DataTable>
            </StepContainer>
        </template>

        <p v-else class="no-selection">
            Choose an application to see and add its roles.
        </p>
    </div>
</template>

<style lang="scss">
.manage-roles-container {
    /*
        Whitespace under the Existing roles table, which is the last thing on the
        page - it otherwise ended flush against the container, measured at 0.
    */
    padding-bottom: 2.5rem;

    /*
        Tightens the gap between the "Existing roles" heading and its table, from
        the h3's own 20px to 12px. Scoped to that section by the class on the
        StepContainer, so the "Create a role" heading keeps its spacing - the
        form below it needs the room, the table does not.
    */
    .existing-roles > .title {
        margin-bottom: 0.75rem;
    }

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

    .role-form {
        display: flex;
        flex-direction: column;
        gap: 1.5rem;
        max-width: 32rem;
    }

    .form-actions {
        display: flex;
        gap: 1rem;
        margin-top: 2rem;
    }

    /*
        The subsection title is a span in a div with no margin of its own; it
        used to be followed by a subtitle paragraph, which is what separated it
        from the checkboxes. With the subtitle gone the gap measured 0.
    */
    .field .subsection-title-container {
        margin-bottom: 0.75rem;
    }

    /*
        Tightens the gap between the Create role button and the rule below it,
        from 2.5rem to 1.5rem. Only the top margin: the bottom one still spaces
        the rule from the Existing roles heading.

        `hr.solid` is needed, not just `hr`. StepContainer styles the rule from a
        scoped block - `hr.solid[data-v-...]` - which has the same specificity as
        `.manage-roles-container .step-container > hr` and wins on order, so the
        simpler selector is silently ignored.
    */
    .step-container > hr.solid {
        margin-top: 1.5rem;
    }

    .created-message {
        margin-top: 1rem;
        color: var(--semantic-color-text-secondary);
    }

    .no-selection {
        color: var(--semantic-color-text-secondary);
    }

    .count-unknown {
        color: var(--semantic-color-text-secondary);
    }
}
</style>
