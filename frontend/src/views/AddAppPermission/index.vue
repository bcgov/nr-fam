<script setup lang="ts">
import RoleSelectTable from "@/components/AddPermissions/RoleSelectTable.vue";
import UserSearch from "@/components/Search/UserSearch.vue";
import Button from "@/components/UI/Button.vue";
import PageTitle from "@/components/UI/PageTitle.vue";
import StepContainer from "@/components/UI/StepContainer.vue";
import { ManagePermissionsRoute } from "@/router/routes";
import { AdminMgmtApiService } from "@/services/ApiServiceFactory";
import type { SelectedUser } from "@/types/SelectUserType";
import { useMutation, useQuery, useQueryClient } from "@tanstack/vue-query";
import { UserType, type CssRoleOptionDto } from "fam-api/model";
import { useForm } from "vee-validate";
import { computed, ref } from "vue";
import { useRouter } from "vue-router";
import { selectedApp } from "@/store/ApplicationState";
import {
    AddAppUserPermissionSuccessQuerykey,
    describeGrantError,
    generateCssRequests,
    getDefaultFormData,
    validateAppPermissionForm,
    type AppPermissionFormType,
    type AppPermissionGrantSummary,
    type UserGrantOutcome,
} from "./utils";

/**
 * Grant a CSS role to one or more users.
 *
 * An application is a CSS integration in one environment, so both identify it.
 * A grant becomes one CSS assignment request per user: CSS assigns to a single
 * user at a time, and a scoped grant creates one role per scope value.
 */
const props = defineProps<{
    integrationId: number;
    environment: string;
}>();

const router = useRouter();
const queryClient = useQueryClient();

const rolesQuery = useQuery({
    queryKey: computed(() => [
        "css-roles",
        props.integrationId,
        props.environment,
    ]),
    queryFn: () =>
        AdminMgmtApiService.cssIntegrationsApi
            .getCssApplicationRoles(props.integrationId, props.environment)
            .then((res) => res.data),
    refetchOnMount: true,
});

const roleOptions = computed<CssRoleOptionDto[]>(
    () => rolesQuery.data.value ?? []
);

const { values, setFieldValue, handleSubmit, errors } =
    useForm<AppPermissionFormType>({
        initialValues: getDefaultFormData(UserType.I),
        validationSchema: validateAppPermissionForm(),
    });

const submitError = ref<string | null>(null);

const grantMutation = useMutation({
    mutationFn: async (
        formData: AppPermissionFormType
    ): Promise<AppPermissionGrantSummary> => {
        const requests = generateCssRequests(formData);

        // One call per user. Sequential rather than concurrent: each may create
        // scope roles, and CSS treats creation as find-or-create, so overlapping
        // calls would race on the same role.
        const outcomes: UserGrantOutcome[] = [];
        for (let i = 0; i < requests.length; i++) {
            const user = formData.users[i];
            try {
                const res =
                    await AdminMgmtApiService.cssIntegrationsApi.createCssUserRoleAssignment(
                        props.integrationId,
                        props.environment,
                        requests[i]
                    );
                outcomes.push({ user, results: res.data });
            } catch (error) {
                // Recorded and carried on with. One user being refused - at
                // another organisation, say - must not discard the grants that
                // already succeeded for everybody else, which have happened in
                // CSS and cannot be taken back by failing here.
                outcomes.push({
                    user,
                    results: [],
                    error: describeGrantError(error),
                });
            }
        }

        return {
            applicationName: selectedApp.value?.description ?? props.environment,
            roleName: formData.role?.name ?? "",
            outcomes,
        };
    },
    onSuccess: (summary) => {
        queryClient.invalidateQueries({
            queryKey: [
                "css-user-role-assignments",
                props.integrationId,
                props.environment,
            ],
        });
        // Left for Manage permissions to pick up after the redirect, which is
        // where the outcome is reported.
        queryClient.setQueryData([AddAppUserPermissionSuccessQuerykey], summary);
        router.push({ name: ManagePermissionsRoute.name });
    },
    onError: (error: Error) => {
        // Only reached if the loop itself failed, since per-user failures are
        // captured above.
        submitError.value = error.message;
    },
});

const onSubmit = handleSubmit(
    (formData) => {
        submitError.value = null;
        grantMutation.mutate(formData);
    },
    () => {
        // Without this the button looks broken when the form is incomplete: the
        // field errors appear at the top of a long form, well above the button
        // that was just pressed, so nothing seems to happen.
        submitError.value =
            "Check the highlighted fields above before granting.";
    }
);

const setSelectedUsers = (users: SelectedUser[]) => {
    setFieldValue("users", users);
};

/**
 * Keep the form's domain in step with the search.
 *
 * It decides the `user_type` sent on the grant, which in turn picks the identity
 * provider CSS assigns against - so a stale value grants against the wrong
 * provider, which now fails verification rather than silently doing nothing.
 *
 * UserSearch clears its own selections when the domain changes and emits the
 * empty selection before this, so the users field is already reset.
 */
const setDomain = (domain: UserType) => {
    setFieldValue("domain", domain);
};

const cancel = () => router.push({ name: ManagePermissionsRoute.name });
</script>

<template>
    <div class="add-app-permission-container">
        <PageTitle
            title="Add permission"
            :subtitle="`Grant a role in ${props.environment.toUpperCase()}`"
        />

        <form @submit="onSubmit">
            <StepContainer title="Select users" divider>
                <!--
                    Event names must match what UserSearch declares. Vue treats
                    an unrecognised listener as a fallthrough attribute rather
                    than an error, so a wrong name here is silent: the handler
                    simply never runs.
                -->
                <UserSearch
                    :environment="props.environment"
                    :multi-user-mode="true"
                    @user-selection-update="setSelectedUsers"
                    @user-domain-change="setDomain"
                />
                <span v-if="errors.users" class="field-error">
                    {{ errors.users }}
                </span>
            </StepContainer>

            <StepContainer title="Select a role" divider>
                <RoleSelectTable
                    :environment="props.environment"
                    :role-options="roleOptions"
                    role-field-id="role"
                    forest-clients-field-id="forestClients"
                    districts-field-id="districts"
                    :set-field-value="(field: string, value: any) => setFieldValue(field as any, value)"
                    :form-values="values"
                />
            </StepContainer>

            <div class="form-actions">
                <Button label="Cancel" severity="secondary" @click="cancel" />
                <Button
                    label="Grant permission"
                    type="submit"
                    :disabled="grantMutation.isPending.value"
                />
            </div>

            <p v-if="submitError" class="field-error">{{ submitError }}</p>
        </form>
    </div>
</template>

<style lang="scss">
.add-app-permission-container {
    .form-actions {
        display: flex;
        justify-content: flex-end;
        gap: 1rem;
        margin-top: 2rem;
    }

    .field-error {
        color: var(--semantic-color-text-error, #d8292f);
    }
}
</style>
