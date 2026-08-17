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
import type { CssApplicationOptionDto, CssRoleOptionDto } from "fam-api";
import Column from "primevue/column";
import DataTable from "primevue/datatable";
import InputText from "primevue/inputtext";
import { computed, ref } from "vue";
import {
    MAX_DESCRIPTION_LENGTH,
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

const handleApplicationChange = (event: { value: CssApplicationOptionDto }) => {
    selectedApp.value = event.value;
    created.value = null;
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

const onSubmit = async () => {
    submitError.value = null;
    created.value = null;
    errors.value = {};

    try {
        await validateManageRolesForm().validate(form.value, {
            abortEarly: false,
        });
    } catch (validationError: any) {
        for (const item of validationError.inner ?? []) {
            if (item.path && !errors.value[item.path]) {
                errors.value[item.path] = item.message;
            }
        }
        return;
    }

    createMutation.mutate();
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
                        <Label
                            label-text="Description"
                            for="description"
                            required
                        />
                        <InputText
                            id="description"
                            class="w-100 custom-height"
                            v-model="form.description"
                            placeholder="FREP Administrator"
                            :maxlength="MAX_DESCRIPTION_LENGTH"
                            :invalid="!!errors.description"
                        />
                        <HelperText
                            :text="
                                errors.description ||
                                'How the role reads to an administrator granting it.'
                            "
                            :is-error="!!errors.description"
                        />
                    </div>

                    <div class="field">
                        <SubsectionTitle
                            title="Scope"
                            subtitle="Whether granting this role requires choosing what it applies to"
                        />
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
                        <HelperText :text="describeScope(form)" />
                    </div>
                </div>

                <div class="form-actions">
                    <Button
                        label="Create role"
                        @click="onSubmit"
                        :disabled="createMutation.isPending.value"
                    />
                </div>

                <ErrorText v-if="submitError" show-icon :error-msg="submitError" />

                <p v-if="created" class="created-message">
                    Created <strong>{{ created.name }}</strong>
                    ({{ created.description }}). It can be granted from Manage
                    permissions now.
                </p>
            </StepContainer>

            <StepContainer title="Existing roles">
                <DataTable class="fam-table" :value="existingRoles">
                    <template #empty>
                        {{
                            rolesQuery.isLoading.value
                                ? "Loading roles…"
                                : "This application has no roles yet"
                        }}
                    </template>

                    <Column header="Role" field="name" />

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

    .created-message {
        margin-top: 1rem;
        color: var(--semantic-color-text-secondary);
    }

    .no-selection {
        color: var(--semantic-color-text-secondary);
    }
}
</style>
