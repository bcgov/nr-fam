import { TrashCan } from "@carbon/icons-react";
import {
    Table,
    TableBody,
    TableCell,
    TableContainer,
    TableHead,
    TableHeader,
    TableRow,
} from "@carbon/react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
    UserType,
    type AdminRoleAuthGroup,
    type CssAdministratorRowDto,
} from "fam-api";
import { useState, type FC } from "react";
import { Chip } from "@/components/Chip";
import { TableSkeleton } from "@/components/TableSkeleton";
import { DestructiveModal } from "@/components/DestructiveModal";
import { PLACE_HOLDER } from "@/constants/constants";
import { useErrorToast } from "@/context/notification/useErrorToast";
import { usePermissionToast } from "@/context/notification/usePermissionToast";
import { AdminMgmtApiService } from "@/services/ApiServiceFactory";
import { invalidateAfterAccessChange } from "@/utils/QueryInvalidation";
import { describeError } from "./CssPermissionsTable";
import "./permissionsTable.css";

/**
 * Who administers one application, at one tier.
 *
 * The rows come from <b>FAM's own CSS integration</b>, not the application's -
 * an administrator holds `APP_ADMIN_<id>_<ENV>` there rather than any role on
 * the application itself. That is why they never appear on the Users tab, and
 * why this is a separate read rather than a filter over the same list.
 *
 * Appointing happens on its own screen; removing happens here, from the row.
 * They are split because appointing needs a user search and a role, and removing
 * needs only the row a person is already looking at.
 */
type Props = {
    integrationId: number;
    environment: string;
    tier: AdminRoleAuthGroup;
    appName: string;
};

const isDelegated = (tier: AdminRoleAuthGroup) => tier === "DELEGATED_ADMIN";

const fullNameOf = (row: CssAdministratorRowDto): string =>
    [row.first_name, row.last_name].filter(Boolean).join(" ");

/** Prefers a resolved label - a district's or client's name - over the raw code. */
const scopeTextOf = (row: CssAdministratorRowDto): string =>
    (row.scopes ?? []).map((scope) => scope.label || scope.value).join(", ");

/**
 * What the delegated role is called: "Submitter (CHR)", not "CHR_FREP_EDITOR".
 *
 * Falls back to the code, which is what a role added directly in the CSS console
 * will always have. A technical name beats an empty pill. Same rule as the Role
 * column on the users tab.
 */
const roleLabelOf = (row: CssAdministratorRowDto): string =>
    row.delegated_role_display_name || row.delegated_role_name || "";

/**
 * The domain the row was found under, as the API's user type.
 *
 * The GUID alone does not identify anybody: the same GUID may exist in both
 * directories, so the removal has to name which one.
 */
const userTypeOf = (row: CssAdministratorRowDto): UserType =>
    row.domain === "BCEID" ? UserType.BceidBus : UserType.Idir;

/**
 * A row nothing can be done with.
 *
 * CSS names some holders only by a username FAM cannot take a GUID from, and a
 * removal has nothing to send for those. Disabled rather than hidden, so the row
 * does not look ordinary while its button quietly fails.
 */
const isRemovable = (row: CssAdministratorRowDto) => Boolean(row.user_guid);

export const AdministratorsTable: FC<Props> = ({
    integrationId,
    environment,
    tier,
    appName,
}) => {
    const [removeError, setRemoveError] = useState<string | null>(null);
    const [pendingRemove, setPendingRemove] =
        useState<CssAdministratorRowDto | null>(null);

    const queryClient = useQueryClient();
    const permissionToast = usePermissionToast();

    const administratorsQuery = useQuery({
        queryKey: ["css-administrators", integrationId, environment, tier],
        queryFn: () =>
            AdminMgmtApiService.cssIntegrationsApi
                .getCssApplicationAdministrators(integrationId, environment, tier)
                .then((res) => res.data),
        refetchOnMount: true,
    });

    const rows = administratorsQuery.data ?? [];

    /* Both tiers share four columns; a delegated administrator adds two. */
    const headers = isDelegated(tier)
        ? ["User Name", "Domain", "Full Name", "Email", "May grant", "Scope", "Action"]
        : ["User Name", "Domain", "Full Name", "Email", "Action"];

    const removeMutation = useMutation({
        mutationFn: (row: CssAdministratorRowDto) => {
            const api = AdminMgmtApiService.cssIntegrationsApi;

            if (isDelegated(tier)) {
                return api.deleteCssDelegatedAdmin(integrationId, environment, {
                    user_guid: row.user_guid ?? "",
                    user_type: userTypeOf(row),
                    // The base name and this row's own scopes, which together
                    // rebuild exactly the delegation role the row came from.
                    role_name: row.delegated_role_name ?? "",
                    scopes: (row.scopes ?? []).map((scope) => ({
                        type: scope.type,
                        values: [scope.value],
                    })),
                });
            }

            // No role and no scope: an application administrator is authorised
            // over the application rather than over any one of its roles.
            return api.deleteCssApplicationAdmin(integrationId, environment, {
                user_guid: row.user_guid ?? "",
                user_type: userTypeOf(row),
            });
        },
        onSuccess: (_result, row) => {
            setRemoveError(null);
            setPendingRemove(null);

            const scope = scopeTextOf(row);
            permissionToast.succeeded(
                isDelegated(tier)
                    ? "Delegated admin removed"
                    : "Application admin removed",
                isDelegated(tier)
                    ? `${row.username} can no longer grant ${roleLabelOf(row)}` +
                          `${scope ? ` for ${scope}` : ""} in ${appName}.`
                    : `${row.username} is no longer an application administrator of ` +
                          `${appName}.`
            );

            invalidateAfterAccessChange(queryClient, integrationId, environment);
        },
        onError: (error: unknown) => {
            // The backend names the reason - removing yourself, or another
            // organisation's user - which is worth more than a status code.
            setRemoveError(
                describeError(error, "The administrator could not be removed.")
            );
        },
    });

    /**
     * The backend's own message when there is one.
     *
     * A generic line here hid the actual reason - a missing
     * `CSS_OWN_INTEGRATION_ID`, or a refusal - behind "please try again", which
     * is advice that would not have helped in either case.
     */
    const loadErrorMessage = describeError(
        administratorsQuery.error,
        "The administrators could not be loaded. Please try again."
    );

    useErrorToast({
        when: administratorsQuery.isError,
        title: "The administrators could not be loaded",
        subtitle: loadErrorMessage,
        occurrence: administratorsQuery.errorUpdatedAt,
    });

    /*
        The removal failure was a banner because the reason matters and it had
        to wait to be read. It still waits - error toasts do not expire - and it
        no longer pushes the table down the page to say so.
    */
    useErrorToast({
        when: removeError !== null,
        title: "The administrator could not be removed",
        subtitle: removeError ?? undefined,
        occurrence: removeError,
    });

    return (
        <div className="fam-table administrators-table">
            {administratorsQuery.isLoading ? (
                <TableSkeleton headers={headers} />
            ) : (
            <TableContainer>
                <Table size="md" useZebraStyles>
                    <TableHead>
                        <TableRow>
                            <TableHeader>User Name</TableHeader>
                            <TableHeader>Domain</TableHeader>
                            <TableHeader>Full Name</TableHeader>
                            <TableHeader>Email</TableHeader>
                            {/*
                                Only meaningful for a delegated administrator:
                                they are delegated one role each, so somebody
                                delegated three roles is three rows. An
                                application administrator is delegated nothing in
                                particular.
                            */}
                            {isDelegated(tier) ? (
                                <>
                                    <TableHeader>May grant</TableHeader>
                                    <TableHeader>Scope</TableHeader>
                                </>
                            ) : null}
                            <TableHeader>Action</TableHeader>
                        </TableRow>
                    </TableHead>
                    <TableBody>
                        {rows.length === 0 ? (
                            <TableRow>
                                <TableCell colSpan={headers.length}>
                                    {/*
                                        Not "no administrators" when the list
                                        never arrived - that reads as a fact
                                        about the application rather than about
                                        the request.
                                    */}
                                    {administratorsQuery.isError
                                        ? "The administrators could not be loaded."
                                        : `${appName} has no administrators at this level`}
                                </TableCell>
                            </TableRow>
                        ) : (
                            rows.map((row, index) => (
                                <TableRow
                                    key={`${row.user_guid ?? row.username}-${row.delegated_role_name ?? ""}-${scopeTextOf(row)}-${index}`}
                                >
                                    <TableCell>{row.username}</TableCell>
                                    <TableCell>{row.domain ?? PLACE_HOLDER}</TableCell>
                                    {/*
                                        Blank until the person first signs in:
                                        CSS holds only a username for somebody
                                        who has never logged in.
                                    */}
                                    <TableCell>
                                        {fullNameOf(row) || PLACE_HOLDER}
                                    </TableCell>
                                    <TableCell>{row.email ?? PLACE_HOLDER}</TableCell>

                                    {isDelegated(tier) ? (
                                        <>
                                            {/*
                                                A pill, like the Role column on
                                                the users tab: both answer "which
                                                role", and plain text here read
                                                as a note about the person rather
                                                than as the role itself.
                                            */}
                                            <TableCell>
                                                {roleLabelOf(row) ? (
                                                    <Chip label={roleLabelOf(row)} />
                                                ) : (
                                                    PLACE_HOLDER
                                                )}
                                            </TableCell>
                                            {/*
                                                Its own column rather than a
                                                suffix on the role, matching the
                                                users table: a delegation covering
                                                a district AND a client carries
                                                both, and joining them into one
                                                string reads as a single odd value
                                                rather than two conditions.
                                            */}
                                            <TableCell>
                                                {row.scopes?.length ? (
                                                    <span className="scope-chips">
                                                        {row.scopes.map((scope) => (
                                                            <Chip
                                                                key={`${scope.type}-${scope.value}`}
                                                                label={
                                                                    scope.label ||
                                                                    scope.value
                                                                }
                                                            />
                                                        ))}
                                                    </span>
                                                ) : (
                                                    PLACE_HOLDER
                                                )}
                                            </TableCell>
                                        </>
                                    ) : null}

                                    <TableCell className="action-col">
                                        <div className="nowrap-cell action-button-group">
                                            <button
                                                title={
                                                    isRemovable(row)
                                                        ? "Remove administrator"
                                                        : "This administrator cannot be identified, so they cannot be removed here"
                                                }
                                                aria-label="Remove administrator"
                                                className="btn btn-icon"
                                                type="button"
                                                disabled={
                                                    !isRemovable(row) ||
                                                    removeMutation.isPending
                                                }
                                                onClick={() => setPendingRemove(row)}
                                            >
                                                <TrashCan />
                                            </button>
                                        </div>
                                    </TableCell>
                                </TableRow>
                            ))
                        )}
                    </TableBody>
                </Table>
            </TableContainer>
            )}

            {/*
                Confirmed before it happens: there is no undo, the assignment is
                gone from CSS and only the audit record says it existed.

                Removing yourself is refused by the backend rather than hidden
                here - the frontend is not told its own GUID, and a button that
                silently did nothing would be worse than one that explains why it
                will not.
            */}
            <DestructiveModal
                open={pendingRemove !== null}
                title={
                    isDelegated(tier)
                        ? "Remove delegated admin"
                        : "Remove application admin"
                }
                confirmButtonText="Remove"
                loading={removeMutation.isPending}
                onCancel={() => setPendingRemove(null)}
                onConfirm={() =>
                    pendingRemove && removeMutation.mutate(pendingRemove)
                }
                message={
                    pendingRemove ? (
                        // The two tiers lose different things and the wording
                        // says so.
                        isDelegated(tier) ? (
                            <span>
                                Are you sure you want to stop{" "}
                                <strong>{pendingRemove.username}</strong> from granting{" "}
                                <strong>{roleLabelOf(pendingRemove)}</strong>
                                {scopeTextOf(pendingRemove) ? (
                                    <>
                                        {" "}
                                        for{" "}
                                        <strong>{scopeTextOf(pendingRemove)}</strong>
                                    </>
                                ) : null}{" "}
                                in {appName}? They will lose that immediately, and
                                will keep any other roles they have been delegated.
                            </span>
                        ) : (
                            <span>
                                Are you sure you want to remove{" "}
                                <strong>{pendingRemove.username}</strong> as an
                                application administrator of {appName}? They will
                                immediately lose the ability to administer it,
                                including appointing other administrators.
                            </span>
                        )
                    ) : (
                        ""
                    )
                }
            />
        </div>
    );
};

export default AdministratorsTable;
