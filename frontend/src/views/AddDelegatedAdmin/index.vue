<script setup lang="ts">
import RoleSelectTable from "@/components/AddPermissions/RoleSelectTable.vue";
import UserSearch from "@/components/Search/UserSearch.vue";
import Button from "@/components/UI/Button.vue";
import PageTitle from "@/components/UI/PageTitle.vue";
import StepContainer from "@/components/UI/StepContainer.vue";
import { ManagePermissionsRoute } from "@/router/routes";
import { AdminMgmtApiService } from "@/services/ApiServiceFactory";
import { selectedApp } from "@/store/ApplicationState";
import type { SelectedUser } from "@/types/SelectUserType";
import { useMutation, useQuery, useQueryClient } from "@tanstack/vue-query";
import { UserType, type CssRoleOptionDto } from "fam-api/model";
import { useForm } from "vee-validate";
import { computed, ref } from "vue";
import { useRouter } from "vue-router";
import {
    getDefaultFormData,
    validateAppPermissionForm,
    type AppPermissionFormType,
} from "@/views/AddAppPermission/utils";
import {
    describeAppointmentError,
    toDelegatedAdminRequests,
} from "./utils";

/**
 * Appoint a delegated administrator.
 *
 * A screen of its own rather than a mode of the grant form, because the two
 * answer different questions with the same fields. Here the role is the one the
 * appointee may *hand out*, and the districts or forest clients are the ones
 * they may hand it out for - not what they are being given. The wording carries
 * that difference; sharing one screen would have meant conditionals throughout.
 *
 * One person at a time, as legacy did: appointing is rarer and more consequential
 * than granting, and the confirmation reads better naming one person.
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

/**
 * Every role the application defines.
 *
 * Not filtered to what the appointer may grant themselves: an application
 * administrator may grant everything, so the list is already everything they
 * could delegate. The backend refuses anything beyond their authority.
 */
const roleOptions = computed<CssRoleOptionDto[]>(
    () => rolesQuery.data.value ?? []
);

const { values, setFieldValue, handleSubmit, errors } =
    useForm<AppPermissionFormType>({
        initialValues: getDefaultFormData(UserType.I),
        validationSchema: validateAppPermissionForm(),
    });

const submitError = ref<string | null>(null);

const setSelectedUsers = (users: SelectedUser[]) => {
    // One at a time, so a multi-select would be misleading; take the first.
    setFieldValue("users", users.slice(0, 1));
};

const setDomain = (domain: UserType) => setFieldValue("domain", domain);

const appointMutation = useMutation({
    mutationFn: async (formData: AppPermissionFormType) => {
        const requests = toDelegatedAdminRequests(formData);

        for (const request of requests) {
            await AdminMgmtApiService.cssIntegrationsApi.createCssDelegatedAdmin(
                props.integrationId,
                props.environment,
                request
            );
        }
    },
    onSuccess: () => {
        // The Delegated admins tab is now stale.
        queryClient.invalidateQueries({
            queryKey: [
                "css-administrators",
                props.integrationId,
                props.environment,
            ],
        });
        router.push({ name: ManagePermissionsRoute.name });
    },
    onError: (error: unknown) => {
        submitError.value = describeAppointmentError(error);
    },
});

const onSubmit = handleSubmit(
    (formData) => {
        submitError.value = null;
        appointMutation.mutate(formData);
    },
    () => {
        // Same reason as the grant form: the field errors sit at the top of a
        // long form, well above the button, so without this the button looks
        // broken when something is missing.
        submitError.value =
            "Check the highlighted fields above before appointing.";
    }
);

const cancel = () => router.push({ name: ManagePermissionsRoute.name });

const applicationName = computed(
    () => selectedApp.value?.description ?? props.environment.toUpperCase()
);
</script>

<template>
    <div class="add-delegated-admin-container">
        <PageTitle
            title="Add delegated admin"
            :subtitle="`Let somebody grant one role in ${applicationName}`"
        />

        <form @submit="onSubmit">
            <StepContainer title="Select a user" divider>
                <!--
                    Event names must match what UserSearch declares. Vue treats
                    an unrecognised listener as a fallthrough attribute rather
                    than an error, so a wrong name here is silent.
                -->
                <UserSearch
                    :environment="props.environment"
                    :multi-user-mode="false"
                    @user-selection-update="setSelectedUsers"
                    @user-domain-change="setDomain"
                />
                <span v-if="errors.users" class="field-error">
                    {{ errors.users }}
                </span>
            </StepContainer>

            <StepContainer title="Select the role they may grant" divider>
                <p class="step-note">
                    The delegated admin will be able to grant this role, and
                    only this role. For a scoped role, choose the districts or
                    forest clients they may grant it for - each one is a
                    separate delegation.
                </p>

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
                    label="Add delegated admin"
                    type="submit"
                    :disabled="appointMutation.isPending.value"
                />
            </div>

            <p v-if="submitError" class="field-error">{{ submitError }}</p>
        </form>
    </div>
</template>

<style lang="scss">
.add-delegated-admin-container {
    padding-bottom: 2.5rem;

    .step-note {
        margin-bottom: 1.5rem;
        max-width: 46rem;
        color: var(--semantic-color-text-secondary);
    }

    .form-actions {
        display: flex;
        justify-content: flex-end;
        gap: 1rem;
        margin-top: 2rem;
    }

    .field-error {
        color: var(--semantic-color-text-error);
    }
}
</style>
