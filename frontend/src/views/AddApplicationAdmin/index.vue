<script setup lang="ts">
import UserSearch from "@/components/Search/UserSearch.vue";
import Button from "@/components/UI/Button.vue";
import PageTitle from "@/components/UI/PageTitle.vue";
import StepContainer from "@/components/UI/StepContainer.vue";
import { ManagePermissionsRoute } from "@/router/routes";
import { AdminMgmtApiService } from "@/services/ApiServiceFactory";
import { invalidateAfterAccessChange } from "@/utils/QueryInvalidation";
import { selectedApp } from "@/store/ApplicationState";
import type { SelectedUser } from "@/types/SelectUserType";
import { useMutation, useQueryClient } from "@tanstack/vue-query";
import { UserType } from "fam-api/model";
import { computed, ref } from "vue";
import { useRouter } from "vue-router";

/**
 * Appoint an application administrator.
 *
 * Shorter than appointing a delegated administrator, and that difference is the
 * point: an application administrator is authorised over the *application*, so
 * there is no role to choose and no scope to narrow. Picking a person is the
 * whole form.
 */
const props = defineProps<{
    integrationId: number;
    environment: string;
}>();

const router = useRouter();
const queryClient = useQueryClient();

const selectedUser = ref<SelectedUser | null>(null);
const domain = ref<UserType>(UserType.Idir);
const submitError = ref<string | null>(null);

const setSelectedUsers = (users: SelectedUser[]) => {
    selectedUser.value = users[0] ?? null;
};

const setDomain = (value: UserType) => {
    domain.value = value;
};

const appointMutation = useMutation({
    mutationFn: () =>
        AdminMgmtApiService.cssIntegrationsApi.createCssApplicationAdmin(
            props.integrationId,
            props.environment,
            {
                user_guid: selectedUser.value?.guid ?? "",
                user_type: domain.value,
            }
        ),
    onSuccess: () => {
        invalidateAfterAccessChange(
            queryClient, props.integrationId, props.environment
        );
        router.push({ name: ManagePermissionsRoute.name });
    },
    onError: (error: any) => {
        // Names the actual refusal - appointing yourself, or reaching outside
        // your own organisation - which a generic line would hide.
        submitError.value =
            error?.response?.data?.description ??
            error?.message ??
            "The application administrator could not be appointed.";
    },
});

const onSubmit = (event: Event) => {
    event.preventDefault();
    submitError.value = null;

    if (!selectedUser.value?.guid) {
        submitError.value = "Choose a user before appointing.";
        return;
    }
    appointMutation.mutate();
};

const cancel = () => router.push({ name: ManagePermissionsRoute.name });

const applicationName = computed(
    () => selectedApp.value?.description ?? props.environment.toUpperCase()
);
</script>

<template>
    <div class="add-application-admin-container">
        <PageTitle
            title="Add application admin"
            :subtitle="`Let somebody administer ${applicationName}`"
        />

        <form @submit="onSubmit">
            <StepContainer title="Select a user" divider>
                <p class="step-note">
                    An application admin can grant and revoke every role this
                    application defines, and can appoint delegated admins for
                    it. They cannot create or delete roles.
                </p>

                <UserSearch
                    :environment="props.environment"
                    :multi-user-mode="false"
                    @user-selection-update="setSelectedUsers"
                    @user-domain-change="setDomain"
                />
            </StepContainer>

            <div class="form-actions">
                <Button label="Cancel" severity="secondary" @click="cancel" />
                <Button
                    label="Add application admin"
                    type="submit"
                    :disabled="appointMutation.isPending.value"
                />
            </div>

            <p v-if="submitError" class="field-error">{{ submitError }}</p>
        </form>
    </div>
</template>

<style lang="scss">
.add-application-admin-container {
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
        /* The rule above already separates these from the form. */
        margin-top: 0.5rem;
    }

    .field-error {
        color: var(--semantic-color-text-error);
    }
}
</style>
