<script setup lang="ts">
import RoleMultiSelectTable from "@/components/AddPermissions/RoleMultiSelectTable.vue";
import RoleScopeCard from "@/components/AddPermissions/RoleScopeCard.vue";
import Chip from "@/components/UI/Chip.vue";
import UserSearch from "@/components/Search/UserSearch.vue";
import Button from "@/components/UI/Button.vue";
import PageTitle from "@/components/UI/PageTitle.vue";
import StepContainer from "@/components/UI/StepContainer.vue";
import { usePermissionToast } from "@/composables/usePermissionToast";
import { invalidateAfterAccessChange } from "@/utils/QueryInvalidation";
import { ManagePermissionsRoute } from "@/router/routes";
import { toGrantToast } from "@/views/ManagePermissionsView/utils";
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
    getDefaultFormData,
    newRoleScopeSelection,
    planGrants,
    requiresScope,
    roleLabel,
    selectionsOverTheLimit,
    totalPermissions,
    validateAppPermissionForm,
    type AppPermissionFormType,
    type AppPermissionGrantSummary,
    type RoleOption,
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
const permissionToast = usePermissionToast();

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
        initialValues: getDefaultFormData(UserType.Idir),
        validationSchema: validateAppPermissionForm(),
    });

const submitError = ref<string | null>(null);

const grantMutation = useMutation({
    mutationFn: async (
        formData: AppPermissionFormType
    ): Promise<AppPermissionGrantSummary> => {
        const planned = planGrants(formData);

        // One call per user per role. Sequential rather than concurrent: each
        // may create scope roles, and CSS treats creation as find-or-create, so
        // overlapping calls would race on the same role.
        const outcomes: UserGrantOutcome[] = [];
        for (const { user, role, request } of planned) {
            try {
                const res =
                    await AdminMgmtApiService.cssIntegrationsApi.createCssUserRoleAssignment(
                        props.integrationId,
                        props.environment,
                        request
                    );
                outcomes.push({ user, role, results: res.data });
            } catch (error) {
                // Recorded and carried on with. One pair being refused - a user
                // at another organisation, or a role they may not be given -
                // must not discard the grants that already succeeded, which have
                // happened in CSS and cannot be taken back by failing here.
                outcomes.push({
                    user,
                    role,
                    results: [],
                    error: describeGrantError(error),
                });
            }
        }

        return {
            applicationName: selectedApp.value?.description ?? props.environment,
            outcomes,
        };
    },
    onSuccess: (summary) => {
        // `refetchType: "all"` because the table is not mounted yet - the
        // redirect is still to come. Invalidating alone only marks it stale, and
        // whether a stale query refetches on mount depends on options set three
        // files away; asking for the refetch outright does not.
        invalidateAfterAccessChange(
            queryClient, props.integrationId, props.environment
        );

        // Raised before the redirect and survives it: the Toast lives in App.vue,
        // above the router view.
        const toast = toGrantToast(summary);
        if (toast) {
            const notify =
                toast.severity === "success"
                    ? permissionToast.succeeded
                    : permissionToast.partiallySucceeded;
            notify(toast.summary, toast.detail);
        }

        // Still left for Manage permissions to pick up: the rows to mark "New",
        // and the banner for anything that failed. Only the plain success half
        // became a toast.
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

        if (selectionsOverTheLimit(formData.roles).length > 0) {
            submitError.value =
                "One of the roles covers more scopes than a single grant can carry. Narrow it before granting.";
            return;
        }
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
 * Nothing beyond the first step is worth showing before somebody is chosen.
 *
 * The roles and their scope are what those people are being given, so the step
 * reads as an unanswerable question without them - and it put a role table and
 * an empty scope picker in front of somebody who had not yet said who this was
 * for.
 */
const hasUser = computed(() => values.users.length > 0);

const selectedRoles = computed(() => values.roles ?? []);

const selectedRoleNames = computed(() =>
    selectedRoles.value.map((selection) => selection.role.name)
);

/** Only these need a card; the rest are granted outright. */
const scopedSelections = computed(() =>
    selectedRoles.value.filter((selection) => requiresScope(selection.role))
);

const unscopedSelections = computed(() =>
    selectedRoles.value.filter((selection) => !requiresScope(selection.role))
);

/**
 * Ticking a role adds it; unticking drops it and everything chosen for it.
 *
 * The scope goes with the role rather than being kept in case it comes back: a
 * silently retained selection would be re-submitted by somebody who thought they
 * had cleared it.
 */
const toggleRole = (role: RoleOption) => {
    const existing = selectedRoles.value.findIndex(
        (selection) => selection.role.name === role.name
    );

    if (existing >= 0) {
        setFieldValue(
            "roles",
            selectedRoles.value.filter((_, index) => index !== existing)
        );
        return;
    }
    setFieldValue("roles", [
        ...selectedRoles.value,
        newRoleScopeSelection(role),
    ]);
};

const removeRole = (roleName: string) => {
    setFieldValue(
        "roles",
        selectedRoles.value.filter(
            (selection) => selection.role.name !== roleName
        )
    );
};

/** Where this role sits in the form, so its pickers can address their fields. */
const fieldPathOf = (roleName: string) =>
    `roles[${selectedRoleNames.value.indexOf(roleName)}]`;

const permissionTotal = computed(() => totalPermissions(values));

const overTheLimit = computed(() => selectionsOverTheLimit(values.roles ?? []));

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

            <StepContainer
                v-if="hasUser"
                title="Select the roles to grant"
                divider
            >
                <p class="step-note">
                    Everybody chosen above gets every role selected here. Pick as
                    many as they should have.
                </p>

                <span v-if="errors.roles" class="field-error">
                    {{ errors.roles }}
                </span>

                <RoleMultiSelectTable
                    :role-options="roleOptions"
                    :selected-role-names="selectedRoleNames"
                    :on-toggle="toggleRole"
                />

                <!--
                    The roles that need nothing further, listed here rather than
                    given an empty card below. A card with no pickers in it reads
                    as one that failed to load.
                -->
                <div v-if="unscopedSelections.length" class="unscoped-summary">
                    <span class="unscoped-label">
                        Granted for the whole application:
                    </span>
                    <Chip
                        v-for="selection in unscopedSelections"
                        :key="selection.role.name"
                        :label="roleLabel(selection.role)"
                    />
                </div>
            </StepContainer>

            <!--
                Only when something actually needs narrowing. A role granted
                outright has nothing to choose, so the step would be an empty
                heading.
            -->
            <StepContainer
                v-if="scopedSelections.length"
                title="Choose what each role applies to"
                divider
            >
                <p class="step-note">
                    Each of these roles is scoped, so it is granted for the
                    districts or organizations you choose and no others.
                </p>

                <RoleScopeCard
                    v-for="selection in scopedSelections"
                    :key="selection.role.name"
                    :selection="selection"
                    :field-path="fieldPathOf(selection.role.name)"
                    :environment="props.environment"
                    :set-field-value="
                        (field: string, value: any) =>
                            setFieldValue(field as any, value)
                    "
                    :on-remove="() => removeRole(selection.role.name)"
                    district-title="Districts this role is granted for"
                    district-subtitle="Select one or more districts for this role"
                    client-title="Organizations this role is granted for"
                    client-subtitle="Add one or more organizations for this role"
                />
            </StepContainer>

            <!--
                The running total, where the decision is made. Every user gets
                every role, and a compound role applies per district/organization
                pair, so the number grows faster than the selections suggest.
            -->
            <p v-if="permissionTotal > 0" class="permission-total">
                This will create
                <strong>{{ permissionTotal }}</strong>
                {{ permissionTotal === 1 ? "permission" : "permissions" }}.
            </p>

            <div class="form-actions">
                <Button label="Cancel" severity="secondary" @click="cancel" />
                <Button
                    label="Grant permission"
                    type="submit"
                    :disabled="
                        grantMutation.isPending.value || overTheLimit.length > 0
                    "
                />
            </div>

            <p v-if="submitError" class="field-error">{{ submitError }}</p>
        </form>
    </div>
</template>

<style lang="scss">
.add-app-permission-container {
    .step-note {
        margin-bottom: 1.5rem;
        max-width: 46rem;
        color: var(--semantic-color-text-secondary);
    }

    .unscoped-summary {
        display: flex;
        flex-wrap: wrap;
        align-items: center;
        gap: 0.5rem;
        margin-top: 1.25rem;
    }

    .unscoped-label {
        color: var(--semantic-color-text-secondary);
    }

    .permission-total {
        margin-top: 1.25rem;
        color: var(--semantic-color-text-secondary);
    }

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
