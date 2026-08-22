<script setup lang="ts">
import RoleMultiSelectTable from "@/components/AddPermissions/RoleMultiSelectTable.vue";
import UserSearch from "@/components/Search/UserSearch.vue";
import Button from "@/components/UI/Button.vue";
import Chip from "@/components/UI/Chip.vue";
import PageTitle from "@/components/UI/PageTitle.vue";
import StepContainer from "@/components/UI/StepContainer.vue";
import { usePermissionToast } from "@/composables/usePermissionToast";
import { ManagePermissionsRoute } from "@/router/routes";
import { AdminMgmtApiService } from "@/services/ApiServiceFactory";
import { selectedApp } from "@/store/ApplicationState";
import type { SelectedUser } from "@/types/SelectUserType";
import type { RoleOption } from "@/views/AddAppPermission/utils";
import { useMutation, useQuery, useQueryClient } from "@tanstack/vue-query";
import { UserType, type CssRoleOptionDto } from "fam-api/model";
import { useForm } from "vee-validate";
import { computed, ref } from "vue";
import { useRouter } from "vue-router";
import RoleScopeCard from "@/components/AddPermissions/RoleScopeCard.vue";
import {
    describeAppointmentError,
    getDefaultFormData,
    newRoleSelection,
    requiresScope,
    roleLabel,
    rolesOverTheLimit,
    toDelegatedAdminRequests,
    totalDelegations,
    validateDelegatedAdminForm,
    type DelegatedAdminFormType,
} from "./utils";

/**
 * Appoint a delegated administrator.
 *
 * A screen of its own rather than a mode of the grant form, because the two
 * answer different questions with the same fields. Here the roles are the ones
 * the appointee may *hand out*, and the districts or forest clients are the ones
 * they may hand them out for - not what they are being given.
 *
 * <b>The form is revealed a step at a time.</b> Roles appear once a user is
 * chosen; the scope cards appear once a role that needs narrowing is ticked.
 * Everything was on screen at once before, which put a role table and two empty
 * scope pickers in front of somebody who had not yet said who this was for.
 *
 * One person at a time, as legacy did: appointing is rarer and more
 * consequential than granting, and the confirmation reads better naming one
 * person. Several roles at once, though - somebody trusted to hand out one role
 * is usually trusted with its neighbours, and appointing them one at a time
 * meant walking this form three times.
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
    useForm<DelegatedAdminFormType>({
        initialValues: getDefaultFormData(UserType.Idir),
        validationSchema: validateDelegatedAdminForm(),
    });

const submitError = ref<string | null>(null);

const setSelectedUsers = (users: SelectedUser[]) => {
    // One at a time, so a multi-select would be misleading; take the first.
    setFieldValue("users", users.slice(0, 1));
};

const setDomain = (domain: UserType) => setFieldValue("domain", domain);

/** Nothing beyond the first step is worth showing before this is answered. */
const hasUser = computed(() => values.users.length > 0);

const selectedRoles = computed(() => values.roles ?? []);

const selectedRoleNames = computed(() =>
    selectedRoles.value.map((selection) => selection.role.name)
);

/** Only these need a card; the rest are delegated outright. */
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
    setFieldValue("roles", [...selectedRoles.value, newRoleSelection(role)]);
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

const delegationTotal = computed(() => totalDelegations(values));

const overTheLimit = computed(() => rolesOverTheLimit(values));

const applicationName = computed(
    () => selectedApp.value?.description ?? props.environment.toUpperCase()
);

const appointMutation = useMutation({
    mutationFn: async (formData: DelegatedAdminFormType) => {
        const requests = toDelegatedAdminRequests(formData);

        // Sequential and per role, recording what happened to each. A refusal on
        // one role must not discard the delegations already made for the others,
        // which have happened in CSS and cannot be taken back by throwing here.
        const failures: string[] = [];
        let appointed = 0;

        for (let i = 0; i < requests.length; i++) {
            try {
                await AdminMgmtApiService.cssIntegrationsApi.createCssDelegatedAdmin(
                    props.integrationId,
                    props.environment,
                    requests[i]
                );
                appointed++;
            } catch (error) {
                failures.push(
                    `${roleLabel(formData.roles[i].role)}: ${describeAppointmentError(error)}`
                );
            }
        }

        return { appointed, failures, userName: formData.users[0]?.userId ?? "" };
    },
    onSuccess: ({ appointed, failures, userName }) => {
        // The Delegated admins tab is now stale. `refetchType: "all"` because
        // the tab is not mounted yet - the redirect is still to come.
        queryClient.invalidateQueries({
            queryKey: [
                "css-administrators",
                props.integrationId,
                props.environment,
            ],
            refetchType: "all",
        });

        if (appointed === 0) {
            // Nothing landed, so there is nothing to confirm and nowhere better
            // than this screen to say why - the form is still filled in.
            submitError.value = failures.join(" ");
            return;
        }

        const roleWord = appointed === 1 ? "role" : "roles";
        if (failures.length > 0) {
            permissionToast.partiallySucceeded(
                "Some roles were not delegated",
                `${userName} can now grant ${appointed} ${roleWord} in ${applicationName.value}. `
                    + `${failures.length} could not be delegated.`
            );
        } else {
            permissionToast.succeeded(
                "Delegated admin added",
                `${userName} can now grant ${appointed} ${roleWord} in ${applicationName.value}.`
            );
        }

        router.push({ name: ManagePermissionsRoute.name });
    },
    onError: (error: unknown) => {
        // Only reached if the loop itself failed, since per-role failures are
        // captured above.
        submitError.value = describeAppointmentError(error);
    },
});

const onSubmit = handleSubmit(
    (formData) => {
        submitError.value = null;

        if (rolesOverTheLimit(formData).length > 0) {
            submitError.value =
                "One of the roles covers more scopes than a single delegation can carry. Narrow it before appointing.";
            return;
        }
        appointMutation.mutate(formData);
    },
    () => {
        // Same reason as the grant form: the field errors sit beside the fields,
        // well above the button, so without this the button looks broken when
        // something is missing.
        submitError.value =
            "Check the highlighted fields above before appointing.";
    }
);

const cancel = () => router.push({ name: ManagePermissionsRoute.name });
</script>

<template>
    <div class="add-delegated-admin-container">
        <PageTitle
            title="Add delegated admin"
            :subtitle="`Let somebody grant roles in ${applicationName}`"
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

            <!--
                Withheld until there is somebody to delegate to. The roles are
                only meaningful as "what this person may hand out", and the step
                reads as an unanswerable question without them.
            -->
            <StepContainer
                v-if="hasUser"
                title="Select the roles they may grant"
                divider
            >
                <p class="step-note">
                    A delegated admin can grant and revoke the roles chosen here,
                    and nothing else. Pick as many as they should be able to hand
                    out.
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
                Only when something actually needs narrowing. A role delegated
                outright has nothing to choose, so the step would be an empty
                heading.
            -->
            <StepContainer
                v-if="scopedSelections.length"
                title="Choose what they may grant it for"
                divider
            >
                <p class="step-note">
                    Each of these roles is scoped, so a delegation covers the
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
                    count-noun="delegation"
                    district-title="Districts they may grant it for"
                    district-subtitle="They will be able to grant this role for these districts, and no others"
                    client-title="Organizations they may grant it for"
                    client-subtitle="They will be able to grant this role for these organizations, and no others"
                />
            </StepContainer>

            <!--
                The running total, where the decision is made. A compound role is
                delegated per district/organization pair, so the number grows
                faster than the selections suggest.
            -->
            <p v-if="delegationTotal > 0" class="delegation-total">
                This will create
                <strong>{{ delegationTotal }}</strong>
                {{ delegationTotal === 1 ? "delegation" : "delegations" }}.
            </p>

            <div class="form-actions">
                <Button label="Cancel" severity="secondary" @click="cancel" />
                <Button
                    label="Add delegated admin"
                    type="submit"
                    :disabled="
                        appointMutation.isPending.value || overTheLimit.length > 0
                    "
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

    .delegation-total {
        margin-top: 1.25rem;
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
