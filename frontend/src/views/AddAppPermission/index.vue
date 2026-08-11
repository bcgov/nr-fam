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
import {
    generateCssRequests,
    getDefaultFormData,
    validateAppPermissionForm,
    type AppPermissionFormType,
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
    mutationFn: async (formData: AppPermissionFormType) => {
        const requests = generateCssRequests(formData);

        // One call per user. Sequential rather than concurrent: each may create
        // scope roles, and CSS treats creation as find-or-create, so overlapping
        // calls would race on the same role.
        const results = [];
        for (const request of requests) {
            const res =
                await AdminMgmtApiService.cssIntegrationsApi.createCssUserRoleAssignment(
                    props.integrationId,
                    props.environment,
                    request
                );
            results.push(...res.data);
        }
        return results;
    },
    onSuccess: () => {
        queryClient.invalidateQueries({
            queryKey: [
                "css-user-role-assignments",
                props.integrationId,
                props.environment,
            ],
        });
        router.push({ name: ManagePermissionsRoute.name });
    },
    onError: (error: Error) => {
        submitError.value = error.message;
    },
});

const onSubmit = handleSubmit((formData) => {
    submitError.value = null;
    grantMutation.mutate(formData);
});

const setSelectedUsers = (users: SelectedUser[]) => {
    setFieldValue("users", users);
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
                <UserSearch
                    :environment="props.environment"
                    :multi-user-mode="true"
                    @update:selected-users="setSelectedUsers"
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
        gap: 1rem;
        margin-top: 2rem;
    }

    .field-error {
        color: var(--semantic-color-text-error, #d8292f);
    }
}
</style>
