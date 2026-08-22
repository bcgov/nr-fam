<script setup lang="ts">
import { usePermissionToast } from "@/composables/usePermissionToast";
import Chip from "@/components/UI/Chip.vue";
import ErrorText from "@/components/UI/ErrorText.vue";
import { PLACE_HOLDER } from "@/constants/constants";
import { AdminMgmtApiService } from "@/services/ApiServiceFactory";
import TrashIcon from "@carbon/icons-vue/es/trash-can/16";
import { useMutation, useQuery, useQueryClient } from "@tanstack/vue-query";
import {
    UserType,
    type AdminRoleAuthGroup,
    type CssAdministratorRowDto,
} from "fam-api";
import Column from "primevue/column";
import ConfirmDialog from "primevue/confirmdialog";
import DataTable from "primevue/datatable";
import { useConfirm } from "primevue/useconfirm";
import { computed, ref } from "vue";
import RemoveAdminDialogText from "./RemoveAdminDialogText.vue";

/**
 * Who administers one application, at one tier.
 *
 * The rows come from **FAM's own CSS integration**, not the application's - an
 * administrator holds `APP_ADMIN_<id>_<ENV>` there rather than any role on the
 * application itself. That is why they never appear on the Users tab, and why
 * this is a separate read rather than a filter over the same list.
 *
 * Appointing happens on its own screen; removing happens here, from the row.
 * They are split because appointing needs a user search and a role, and removing
 * needs only the row a person is already looking at.
 */
const props = defineProps<{
    integrationId: number;
    environment: string;
    tier: AdminRoleAuthGroup;
    appName: string;
}>();

const confirm = useConfirm();
const queryClient = useQueryClient();
const permissionToast = usePermissionToast();

const removeError = ref<string | null>(null);

/** Wording for the confirmation, set just before the dialog opens. */
const confirmTextProps = ref<{
    userName: string;
    role?: string | null;
    scope?: string | null;
} | null>(null);

/**
 * Per tier, because both tables are mounted by the same screen.
 *
 * A shared group name would have every mounted dialog answer the same request,
 * so confirming on the Delegated admins tab would also fire whatever the
 * Application admins tab had queued.
 */
const confirmGroup = computed(() => `removeAdministrator-${props.tier}`);

const administratorsQueryKey = computed(() => [
    "css-administrators",
    props.integrationId,
    props.environment,
    props.tier,
]);

const administratorsQuery = useQuery({
    queryKey: administratorsQueryKey,
    queryFn: () =>
        AdminMgmtApiService.cssIntegrationsApi
            .getCssApplicationAdministrators(
                props.integrationId,
                props.environment,
                props.tier
            )
            .then((res) => res.data),
    refetchOnMount: true,
});

const rows = computed<CssAdministratorRowDto[]>(
    () => administratorsQuery.data.value ?? []
);

/**
 * The backend's own message when there is one.
 *
 * A generic line here hid the actual reason - a missing
 * `CSS_OWN_INTEGRATION_ID`, or a refusal - behind "please try again", which is
 * advice that would not have helped in either case.
 */
const errorMessage = computed(() => {
    const error = administratorsQuery.error.value as any;
    return (
        error?.response?.data?.description ??
        error?.message ??
        "The administrators could not be loaded. Please try again."
    );
});

const fullName = (row: CssAdministratorRowDto): string =>
    [row.first_name, row.last_name].filter(Boolean).join(" ");

/** Prefers a resolved label - a district's or client's name - over the raw code. */
const scopeText = (row: CssAdministratorRowDto): string =>
    (row.scopes ?? []).map((scope) => scope.label || scope.value).join(", ");

/**
 * What the delegated role is called: "Submitter (CHR)", not "CHR_FREP_EDITOR".
 *
 * Falls back to the code, which is what a role added directly in the CSS console
 * will always have. A technical name beats an empty pill. Same rule as the Role
 * column on the users tab.
 */
const roleLabel = (row: CssAdministratorRowDto): string =>
    row.delegated_role_display_name || row.delegated_role_name || "";

/**
 * The domain the row was found under, as the API's user type.
 *
 * The GUID alone does not identify anybody: the same GUID may exist in both
 * directories, so the removal has to name which one.
 */
const userTypeOf = (row: CssAdministratorRowDto): UserType =>
    row.domain === "BCEID" ? UserType.BceidBus : UserType.Idir;

const removeMutation = useMutation({
    mutationFn: (row: CssAdministratorRowDto) => {
        const api = AdminMgmtApiService.cssIntegrationsApi;

        if (props.tier === "DELEGATED_ADMIN") {
            return api.deleteCssDelegatedAdmin(
                props.integrationId,
                props.environment,
                {
                    user_guid: row.user_guid ?? "",
                    user_type: userTypeOf(row),
                    // The base name and this row's own scopes, which together
                    // rebuild exactly the delegation role the row came from.
                    role_name: row.delegated_role_name ?? "",
                    scopes: (row.scopes ?? []).map((scope) => ({
                        type: scope.type,
                        values: [scope.value],
                    })),
                }
            );
        }

        // No role and no scope: an application administrator is authorised over
        // the application rather than over any one of its roles.
        return api.deleteCssApplicationAdmin(
            props.integrationId,
            props.environment,
            {
                user_guid: row.user_guid ?? "",
                user_type: userTypeOf(row),
            }
        );
    },
    onSuccess: (_result, row) => {
        removeError.value = null;

        const scope = scopeText(row);
        permissionToast.succeeded(
            props.tier === "DELEGATED_ADMIN"
                ? "Delegated admin removed"
                : "Application admin removed",
            props.tier === "DELEGATED_ADMIN"
                ? `${row.username} can no longer grant ${roleLabel(row)}`
                      + `${scope ? ` for ${scope}` : ""} in ${props.appName}.`
                : `${row.username} is no longer an application administrator of `
                      + `${props.appName}.`
        );

        queryClient.invalidateQueries({
            queryKey: administratorsQueryKey.value,
        });
    },
    onError: (error: any) => {
        // The backend names the reason - removing yourself, or another
        // organisation's user - which is worth more than a status code.
        removeError.value =
            error?.response?.data?.description ??
            error?.message ??
            "The administrator could not be removed.";
    },
});

/**
 * Confirmed before it happens.
 *
 * There is no undo: the assignment is gone from CSS and only the audit record
 * says it existed.
 *
 * Removing yourself is refused by the backend rather than hidden here - the
 * frontend is not told its own GUID, and a button that silently did nothing
 * would be worse than one that explains why it will not.
 */
const confirmRemove = (row: CssAdministratorRowDto) => {
    confirmTextProps.value = {
        userName: row.username,
        role: roleLabel(row),
        scope: scopeText(row),
    };

    confirm.require({
        group: confirmGroup.value,
        header:
            props.tier === "DELEGATED_ADMIN"
                ? "Remove delegated admin"
                : "Remove application admin",
        acceptLabel: "Remove",
        rejectLabel: "Cancel",
        acceptProps: { severity: "danger" },
        rejectProps: { severity: "secondary", outlined: true },
        accept: () => removeMutation.mutate(row),
    });
};

/**
 * A row nothing can be done with.
 *
 * CSS names some holders only by a username FAM cannot take a GUID from, and a
 * removal has nothing to send for those. Disabled rather than hidden, so the
 * row does not look ordinary while its button quietly fails.
 */
const isRemovable = (row: CssAdministratorRowDto) => Boolean(row.user_guid);
</script>

<template>
    <div class="fam-table administrators-table">
        <!--
            Always mounted, with only the wording conditional. Mounting the
            dialog at the moment it is asked to open would risk it missing the
            request that opened it.
        -->
        <ConfirmDialog :group="confirmGroup">
            <template #message>
                <RemoveAdminDialogText
                    v-if="confirmTextProps"
                    :tier="tier"
                    :user-name="confirmTextProps.userName"
                    :role="confirmTextProps.role"
                    :scope="confirmTextProps.scope"
                    :app-name="appName"
                />
            </template>
        </ConfirmDialog>

        <ErrorText
            v-if="administratorsQuery.isError.value"
            show-icon
            :error-msg="errorMessage"
        />

        <ErrorText v-if="removeError" show-icon :error-msg="removeError" />

        <DataTable class="fam-table" :value="rows">
            <template #empty>
                {{
                    administratorsQuery.isLoading.value
                        ? "Loading administrators…"
                        : `${appName} has no administrators at this level`
                }}
            </template>

            <Column header="User Name" field="username" />

            <Column header="Domain">
                <template #body="{ data }">
                    {{ data.domain ?? PLACE_HOLDER }}
                </template>
            </Column>

            <Column header="Full Name">
                <template #body="{ data }">
                    <!--
                        Blank until the person first signs in: CSS holds only a
                        username for somebody who has never logged in.
                    -->
                    {{ fullName(data) || PLACE_HOLDER }}
                </template>
            </Column>

            <Column header="Email">
                <template #body="{ data }">
                    {{ data.email ?? PLACE_HOLDER }}
                </template>
            </Column>

            <!--
                Only meaningful for a delegated administrator: they are delegated
                one role each, so somebody delegated three roles is three rows.
                An application administrator is delegated nothing in particular.
            -->
            <template v-if="tier === 'DELEGATED_ADMIN'">
                <Column header="May grant">
                    <template #body="{ data }">
                        <!--
                            A pill, like the Role column on the users tab: both
                            answer "which role", and plain text here read as a
                            note about the person rather than as the role itself.
                        -->
                        <Chip v-if="roleLabel(data)" :label="roleLabel(data)" />
                        <span v-else>{{ PLACE_HOLDER }}</span>
                    </template>
                </Column>

                <!--
                    Its own column rather than a suffix on the role, matching the
                    users table: a delegation covering a district AND a client
                    carries both, and joining them into one string reads as a
                    single odd value rather than two conditions.
                -->
                <Column header="Scope">
                    <template #body="{ data }">
                        <span v-if="!data.scopes?.length">{{
                            PLACE_HOLDER
                        }}</span>
                        <span v-else class="scope-chips">
                            <Chip
                                v-for="scope in data.scopes"
                                :key="`${scope.type}-${scope.value}`"
                                :label="scope.label || scope.value"
                            />
                        </span>
                    </template>
                </Column>
            </template>

            <Column header="Action" class="action-col">
                <template #body="{ data }">
                    <div class="nowrap-cell action-button-group">
                        <button
                            :title="
                                isRemovable(data)
                                    ? 'Remove administrator'
                                    : 'This administrator cannot be identified, so they cannot be removed here'
                            "
                            class="btn btn-icon"
                            type="button"
                            :disabled="
                                !isRemovable(data) ||
                                removeMutation.isPending.value
                            "
                            @click="confirmRemove(data)"
                        >
                            <TrashIcon />
                        </button>
                    </div>
                </template>
            </Column>
        </DataTable>
    </div>
</template>

<style lang="scss">
@use "./permissionsTable.scss";
</style>
