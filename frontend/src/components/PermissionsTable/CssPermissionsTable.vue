<script setup lang="ts">
import { usePermissionToast } from "@/composables/usePermissionToast";
import { invalidateAfterAccessChange } from "@/utils/QueryInvalidation";
import TableSkeleton from "@/components/Skeletons/TableSkeleton.vue";
import { TABLE_DATATABLE_PT } from "@/passthrough/datatable/datatablePassThrough";
import TableHeaderTitle from "@/components/Table/TableHeaderTitle.vue";
import TableToolbar from "@/components/Table/TableToolbar.vue";
import Button from "@/components/UI/Button.vue";
import Chip from "@/components/UI/Chip.vue";
import ErrorText from "@/components/UI/ErrorText.vue";
import Spinner from "@/components/UI/Spinner.vue";
import {
    DEFAULT_ROW_PER_PAGE,
    MINIMUM_SEARCH_STR_LEN,
    PLACE_HOLDER,
    TABLE_CURRENT_PAGE_REPORT_TEMPLATE,
    TABLE_PAGINATOR_TEMPLATE,
    TABLE_ROWS_PER_PAGE,
} from "@/constants/constants";
import { AdminMgmtApiService } from "@/services/ApiServiceFactory";
import { UserPermissionHistoryRoute } from "@/router/routes";
import DownloadIcon from "@carbon/icons-vue/es/download/16";
import RecentlyViewedIcon from "@carbon/icons-vue/es/recently-viewed/16";
import TrashIcon from "@carbon/icons-vue/es/trash-can/16";
import { useMutation, useQuery, useQueryClient } from "@tanstack/vue-query";
import { type CssUserRoleRowDto, UserType } from "fam-api";
import Column from "primevue/column";
import ConfirmDialog from "primevue/confirmdialog";
import DataTable from "primevue/datatable";
import { useConfirm } from "primevue/useconfirm";
import { computed, ref } from "vue";
import { useRouter } from "vue-router";
import ConfirmDialogText from "./ConfirmDialogText.vue";
import NewUserTag from "./NewUserTag.vue";
import {
    downloadPermissionsCsv,
    isNewlyGranted,
    NEW_ACCESS_STYLE_IN_TABLE,
    permissionsTableHeaders,
    roleLabel,
    scopeText,
    toRevokeRequest,
} from "./utils";

/**
 * Permissions sourced from CSS, in the table this screen has always used.
 *
 * A CSS integration is identified by an id and an environment, not by a single
 * FAM application id - one integration spans dev/test/prod - so both are props.
 *
 * <b>Two columns the original had are absent</b>, because CSS has nowhere to
 * hold them: when access was granted, and when it expires. They are left out
 * rather than rendered permanently blank, which would read as missing data
 * rather than as data that does not exist. "Organization" is likewise not the
 * original column: what CSS records is the scope a role was granted for, which
 * is a forest client on some roles and a natural resource district on others.
 */
const props = withDefaults(
    defineProps<{
        integrationId: number;
        environment: string;
        appName: string;
        /** Rows a grant just created, marked "New" until the screen is left. */
        newlyGrantedKeys?: string[];
    }>(),
    { newlyGrantedKeys: () => [] }
);

const isNew = (row: CssUserRoleRowDto) =>
    isNewlyGranted(row, props.newlyGrantedKeys);

/** DataTable takes a style object, or nothing at all for an ordinary row. */
const rowStyle = (row: CssUserRoleRowDto) =>
    isNew(row) ? NEW_ACCESS_STYLE_IN_TABLE : undefined;

const search = ref("");
const searchError = ref<string | null>(null);
const revokeError = ref<string | null>(null);

/** Wording for the revoke confirmation, set just before the dialog opens. */
const confirmTextProps = ref<{
    userName: string;
    role: string;
    scope?: string | null;
    appName: string;
} | null>(null);

const router = useRouter();
const confirm = useConfirm();
const queryClient = useQueryClient();
const permissionToast = usePermissionToast();

const assignmentsQuery = useQuery({
    queryKey: computed(() => [
        "css-user-role-assignments",
        props.integrationId,
        props.environment,
    ]),
    queryFn: () =>
        AdminMgmtApiService.cssIntegrationsApi
            .getCssUserRoleAssignments(props.integrationId, props.environment)
            .then((res) => res.data),
    refetchOnMount: true,
});

const rows = computed<CssUserRoleRowDto[]>(
    () => assignmentsQuery.data.value ?? []
);

const fullName = (row: CssUserRoleRowDto) =>
    [row.first_name, row.last_name].filter(Boolean).join(" ");

/**
 * The rows as the table works with them: each carrying the label its role shows.
 *
 * Derived here rather than read off in the template so the column can sort on
 * it. Sorting on the description alone would gather every role without one at
 * a single end, in no order a person would expect.
 */
type PermissionRow = CssUserRoleRowDto & {
    role_label: string;
    /** Scopes joined, so the column sorts and the keyword search matches. */
    scope_text: string;
};

const tableRows = computed<PermissionRow[]>(() =>
    rows.value.map((row) => ({
        ...row,
        role_label: roleLabel(row),
        scope_text: scopeText(row),
    }))
);

const filteredRows = computed<PermissionRow[]>(() => {
    const term = search.value.trim().toLowerCase();
    // Below the minimum the search is not applied at all, so a half-typed word
    // does not empty the table.
    if (term.length < MINIMUM_SEARCH_STR_LEN) {
        return tableRows.value;
    }
    return tableRows.value.filter((row) =>
        [
            row.username,
            row.email,
            fullName(row),
            // Both, so searching the description or the code finds the row -
            // the code is still what an application authorises on.
            row.role_label,
            row.role_name,
            row.domain,
            // Scope was never searchable, which was tolerable when it was one
            // quiet column. It is chips now, and a district code is the obvious
            // thing to look for.
            row.scope_text,
        ]
            .filter(Boolean)
            .some((field) => String(field).toLowerCase().includes(term))
    );
});

const handleSearchChange = (value: string) => {
    search.value = value;
};

/** Only complains once the field is left, not on every keystroke. */
const handleSearchBlur = () => {
    const term = search.value.trim();
    searchError.value =
        term.length > 0 && term.length < MINIMUM_SEARCH_STR_LEN
            ? `Keyword must have at least ${MINIMUM_SEARCH_STR_LEN} characters`
            : null;
};

/**
 * Built in the browser from the rows already loaded.
 *
 * The original asked the backend for a CSV. There is no such endpoint here, and
 * adding one would mean a second pass over the same CSS fan-out that produced
 * this table.
 */
const downloadCsv = () =>
    downloadPermissionsCsv(filteredRows.value, props.appName);

/**
 * The audit trail for one user in this application.
 *
 * Keyed on the GUID, which the row carries precisely because the displayed
 * username is not a stable identifier.
 */
const goToHistory = (row: PermissionRow) => {
    router.push({
        name: UserPermissionHistoryRoute.name,
        query: {
            targetUserGuid: row.user_guid,
            // The audit keys the target by <TYPE>\<GUID>, so the directory has
            // to travel with the GUID.
            targetUserType:
                row.domain === "BCEID" ? UserType.BceidBus : UserType.Idir,
            integrationId: props.integrationId,
            environment: props.environment,
            userName: row.username,
        },
    });
};

const revokeMutation = useMutation({
    mutationFn: (row: PermissionRow) =>
        AdminMgmtApiService.cssIntegrationsApi.deleteCssUserRoleAssignment(
            props.integrationId,
            props.environment,
            toRevokeRequest(row)
        ),
    onSuccess: (_result, row) => {
        revokeError.value = null;

        // Said out loud, because the only other evidence is a row vanishing -
        // which on a paginated table can happen off-screen.
        const scope = scopeText(row);
        permissionToast.succeeded(
            "Permission removed",
            `${row.role_label}${scope ? ` for ${scope}` : ""} was removed from `
                + `${row.username} in ${props.appName}.`
        );

        invalidateAfterAccessChange(
            queryClient, props.integrationId, props.environment
        );
    },
    onError: (error: any) => {
        // The backend names the reason - a self-revoke, another organisation -
        // which is worth more than a status code.
        revokeError.value =
            error?.response?.data?.description ??
            error?.message ??
            "The permission could not be removed.";
    },
});

/**
 * Confirmed before it happens.
 *
 * Revoking is immediate and there is no undo: the assignment is gone from CSS
 * and only the audit record says it existed.
 */
const confirmRevoke = (row: PermissionRow) => {
    // The dialog renders the wording from these props in its own slot, because
    // PrimeVue's `message` is a string and this needs markup.
    confirmTextProps.value = {
        userName: row.username,
        role: row.role_label,
        scope: scopeText(row),
        appName: props.appName,
    };

    confirm.require({
        group: "revokePermission",
        header: "Remove permission",
        acceptLabel: "Remove",
        rejectLabel: "Cancel",
        acceptProps: { severity: "danger" },
        rejectProps: { severity: "secondary", outlined: true },
        accept: () => revokeMutation.mutate(row),
    });
};
</script>

<template>
    <div class="fam-table">
        <!--
            Always mounted, with only the wording conditional. Mounting the
            dialog at the moment it is asked to open would risk it missing the
            request that opened it.
        -->
        <ConfirmDialog group="revokePermission">
            <template #message>
                <ConfirmDialogText
                    v-if="confirmTextProps"
                    :user-name="confirmTextProps.userName"
                    :role="confirmTextProps.role"
                    :scope="confirmTextProps.scope"
                    :app-name="confirmTextProps.appName"
                />
            </template>
        </ConfirmDialog>

        <TableHeaderTitle
            :title="`${props.appName} users`"
            :description="`This table shows all the users in ${props.appName} and their permissions levels`"
        />

        <ErrorText v-if="searchError" :error-msg="searchError" />
        <ErrorText v-if="revokeError" show-icon :error-msg="revokeError" />

        <div class="table-toolbar-container">
            <TableToolbar
                :filter="search"
                input-placeholder="Search by keyword"
                @change="handleSearchChange"
                @blur="handleSearchBlur"
            />
            <Button
                :disabled="filteredRows.length === 0"
                @click="downloadCsv"
                outlined
                label="Download table as CSV file"
                :icon="DownloadIcon"
                aria-label="Download table as CSV file"
            />
        </div>

        <TableSkeleton
            v-if="assignmentsQuery.isLoading.value"
            :headers="permissionsTableHeaders"
            :row-amount="5"
        />

        <ErrorText
            v-else-if="assignmentsQuery.isError.value"
            error-msg="Failed to load permissions from CSS. Please try again."
        />

        <div v-else>
            <DataTable
                :pt="TABLE_DATATABLE_PT"
                :value="filteredRows"
                removableSort
                stripedRows
                paginator
                :rows="DEFAULT_ROW_PER_PAGE"
                :rows-per-page-options="TABLE_ROWS_PER_PAGE"
                :paginator-template="TABLE_PAGINATOR_TEMPLATE"
                :current-page-report-template="TABLE_CURRENT_PAGE_REPORT_TEMPLATE"
                sort-field="username"
                :sort-order="1"
                :row-style="rowStyle"
                :loading="assignmentsQuery.isFetching.value"
            >
                <template #empty> No user found. </template>
                <template #loading><Spinner /></template>

                <Column header="User Name" field="username" sortable>
                    <template #body="{ data }">
                        <div class="nowrap-cell">
                            <NewUserTag v-if="isNew(data)" />
                            <span>{{ data.username }}</span>
                        </div>
                    </template>
                </Column>

                <Column header="Domain" field="domain" sortable>
                    <template #body="{ data }">
                        <span>{{ data.domain ?? PLACE_HOLDER }}</span>
                    </template>
                </Column>

                <Column header="Full Name" sortable sort-field="first_name">
                    <template #body="{ data }">
                        {{ fullName(data) || PLACE_HOLDER }}
                    </template>
                </Column>

                <Column header="Email" field="email" sortable>
                    <template #body="{ data }">
                        {{ data.email ?? PLACE_HOLDER }}
                    </template>
                </Column>

                <!--
                    A chip per scope. A role scoped by a district AND a forest
                    client carries both, and collapsing them to one string would
                    read as a single odd value rather than two conditions.

                    Sorted on scope_text, because a list of chips has nothing to
                    compare; that field is the same text, joined.
                -->
                <Column header="Scope" field="scope_text" sortable>
                    <template #body="{ data }">
                        <span v-if="!data.scopes?.length">{{ PLACE_HOLDER }}</span>
                        <span v-else class="scope-chips">
                            <Chip
                                v-for="scope in data.scopes"
                                :key="`${scope.type}-${scope.value}`"
                                :label="scope.label || scope.value"
                            />
                        </span>
                    </template>
                </Column>

                <Column header="Role" field="role_label" sortable>
                    <template #body="{ data }">
                        <Chip :label="data.role_label" />
                    </template>
                </Column>

                <Column header="Action" class="action-col">
                    <template #body="{ data }">
                        <div class="nowrap-cell action-button-group">
                            <button
                                title="User permission history"
                                class="btn btn-icon"
                                type="button"
                                @click="goToHistory(data)"
                            >
                                <RecentlyViewedIcon />
                            </button>

                            <button
                                title="Delete user permission"
                                class="btn btn-icon"
                                type="button"
                                :disabled="revokeMutation.isPending.value"
                                @click="confirmRevoke(data)"
                            >
                                <TrashIcon />
                            </button>
                        </div>
                    </template>
                </Column>
            </DataTable>
        </div>
    </div>
</template>

<style lang="scss">
@use "@/passthrough/datatable/datatablePassThrough.scss";
@use "./permissionsTable.scss";
.fam-table {
    /*
        Ported from legacy's ManagePermissionsTable. The differences from what
        was here mattered visually:

        - no padding and no gap, so the row runs edge to edge of the card and the
          search and the CSV button meet on a shared border, as one toolbar strip
          rather than two floating controls;
        - `flex: 5 1 35ch` on the search, so it takes the width instead of
          sitting as a narrow box with space around it;
        - a uniform 2.6rem height, so the two sides line up;
        - square, hairline-bordered buttons, which is what makes the CSV control
          read as part of the strip rather than a filled button.
    */
    .table-toolbar-container {
        display: flex;
        flex-wrap: wrap;
        justify-content: space-between;
        align-items: center;

        > * {
            flex: 1 1 0;
            height: 2.6rem;
        }

        :first-child {
            flex: 5 1 35ch;
        }

        button {
            border-radius: 0;
            border-width: 1px;
            border-style: solid;
            border-color: #dfdfe1;
        }
    }

}
</style>
