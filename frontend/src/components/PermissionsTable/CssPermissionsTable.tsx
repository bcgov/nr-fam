import { Download, Edit, RecentlyViewed } from "@carbon/icons-react";
import {
    Button,
    DataTable,
    Pagination,
    Table,
    TableBody,
    TableCell,
    TableContainer,
    TableHead,
    TableHeader,
    TableRow,
    TableToolbar,
    TableToolbarContent,
    TableToolbarSearch,
} from "@carbon/react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { UserType, type CssUserRoleRowDto } from "fam-api";
import { Fragment, useMemo, useState, type FC } from "react";
import { useNavigate } from "react-router-dom";
import { Chip } from "@/components/Chip";
import { TableSkeleton } from "@/components/TableSkeleton";
import { DestructiveModal } from "@/components/DestructiveModal";
import {
    DEFAULT_ROW_PER_PAGE,
    MINIMUM_SEARCH_STR_LEN,
    PLACE_HOLDER,
    TABLE_ROWS_PER_PAGE,
} from "@/constants/constants";
import { useErrorToast } from "@/context/notification/useErrorToast";
import { usePermissionToast } from "@/context/notification/usePermissionToast";
import { ROUTES } from "@/routes/routePaths";
import { AdminMgmtApiService } from "@/services/ApiServiceFactory";
import { domainLabel, formatFullName } from "@/utils/UserUtils";
import { invalidateAfterAccessChange } from "@/utils/QueryInvalidation";
import { describeApiError } from "@/utils/ApiUtils";
import { NewUserTag } from "./NewUserTag";
import { sortedByRole } from "@/utils/RoleSort";
import {
    formatExpiry,
    groupByRole,
    groupScopeText,
    needsScopeDetail,
    scopeChipLabel,
    type PermissionGroup,
    downloadPermissionsCsv,
    isNewlyGranted,
    permissionsTableHeaders,
    roleLabel,
    scopeText,
    toRevokeRequest,
} from "./utils";
import "./permissionsTable.css";
import { RemoveButton } from "@/components/RemoveButton";

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
type Props = {
    integrationId: number;
    environment: string;
    appName: string;
    /** Rows a grant just created, marked "New" until the screen is left. */
    newlyGrantedKeys?: string[];
};

/**
 * The rows as the table works with them, each carrying the text its columns
 * sort and search on.
 *
 * Derived rather than read off in the cell, so the column can sort on it.
 * Sorting on the role description alone would gather every role without one at
 * a single end, in no order a person would expect.
 */
/**
 * A row, which is one person's hold on one role rather than one CSS assignment.
 *
 * <p>It carries the first assignment's fields - user, role, expiry are the same
 * across a group by construction - plus the group itself, which is what the
 * scope column reads and what a removal has to act on.
 */
type PermissionRow = CssUserRoleRowDto & {
    /** Carbon keys rows by id and hands only the id back in the render prop. */
    id: string;
    full_name: string;
    role_label: string;
    /** Scopes joined, so the column sorts and the keyword search matches. */
    scope_text: string;
    expiry_text: string;
    group: PermissionGroup;
};

const HEADERS = [
    { key: "username", header: "User Name" },
    { key: "domain", header: "Domain" },
    { key: "full_name", header: "Full Name" },
    { key: "email", header: "Email" },
    // Role before scope: the scope only means anything once you know which
    // role it narrows, and a grouped row is one role with several scopes.
    { key: "role_label", header: "Role" },
    { key: "scope_text", header: "Scope" },
    { key: "expiry_text", header: "Expires" },
    { key: "action", header: "Action" },
];

const fullNameOf = (row: CssUserRoleRowDto) =>
    formatFullName(row.first_name, row.last_name, row.username);

/**
 * Unique per assignment, not per user: somebody holding three roles is three
 * rows, and a duplicate id would make Carbon render one of them and drop the
 * rest.
 */
const rowId = (row: CssUserRoleRowDto, index: number) =>
    `${row.user_guid ?? row.username}|${row.role_name}|${row.expires_on ?? ""}|${index}`;

/**
 * Everything one role was granted for, in one cell.
 *
 * <p>Two shapes, and which one is used says something true about the grant.
 *
 * <p>Where a role is scoped one way - three regions, four districts - the values
 * are simply listed. There is nothing to explain: each is an independent grant
 * of the same role, and a list is what that is.
 *
 * <p>Where a role is scoped two ways at once, each pairing gets its own line and
 * the values in it are joined. That is not decoration: a submitter for a
 * district <em>and</em> an organisation holds that role for the pair, and a flat
 * list of four values would read as four grants when it is two.
 */
const ScopeCell: FC<{ group: PermissionGroup }> = ({ group }) => {
    if (group.combinations.length === 0) {
        return <>{PLACE_HOLDER}</>;
    }

    const detailed = needsScopeDetail(group);

    if (!detailed) {
        return (
            <span className="scope-chips">
                {group.combinations.flat().map((scope) => (
                    <Chip
                        key={`${scope.type}-${scope.value}`}
                        label={scopeChipLabel(scope)}
                    />
                ))}
            </span>
        );
    }

    return (
        <div className="scope-combinations">
            {group.combinations.map((combination) => (
                <span
                    className="scope-chips"
                    key={combination.map((scope) => scope.value).join("+")}
                >
                    {combination.map((scope, index) => (
                        <Fragment key={`${scope.type}-${scope.value}`}>
                            {index > 0 ? (
                                <span
                                    className="scope-combinations__join"
                                    aria-hidden="true"
                                >
                                    +
                                </span>
                            ) : null}
                            <Chip label={scopeChipLabel(scope)} />
                        </Fragment>
                    ))}
                </span>
            ))}
        </div>
    );
};

export const CssPermissionsTable: FC<Props> = ({
    integrationId,
    environment,
    appName,
    newlyGrantedKeys = [],
}) => {
    const [search, setSearch] = useState("");
    const [searchError, setSearchError] = useState<string | null>(null);
    const [revokeError, setRevokeError] = useState<string | null>(null);
    const [page, setPage] = useState(1);
    const [pageSize, setPageSize] = useState(DEFAULT_ROW_PER_PAGE);
    /** The row awaiting confirmation, and the only thing that opens the modal. */
    const [pendingRevoke, setPendingRevoke] = useState<PermissionRow | null>(null);

    const navigate = useNavigate();
    const queryClient = useQueryClient();
    const permissionToast = usePermissionToast();

    const assignmentsQuery = useQuery({
        queryKey: ["css-user-role-assignments", integrationId, environment],
        queryFn: () =>
            AdminMgmtApiService.cssIntegrationsApi
                .getCssUserRoleAssignments(integrationId, environment)
                .then((res) => res.data),
        refetchOnMount: true,
    });

    const tableRows = useMemo<PermissionRow[]>(
        () =>
            /*
                Ordered by role before anything else touches it, so the table
                opens the same way every time. There is no role code on an
                assignment - only the name it was granted under - so that is the
                key here. The column headers still re-sort on click; this is
                only what the table looks like before anybody asks.
            */
            /*
                Sorted, then grouped, then mapped. That order matters: grouping
                keeps the order it is given, so sorting first is what makes the
                grouped table come out sorted by role.
            */
            groupByRole(
                sortedByRole(assignmentsQuery.data ?? [], (row) => row.role_name)
            ).map((group, index) => {
                // Same by construction across a group: they are what it was
                // grouped on.
                const first = group.assignments[0];
                return {
                    ...first,
                    id: rowId(first, index),
                    full_name: fullNameOf(first),
                    role_label: roleLabel(first),
                    // Derived here rather than in the cell so the column sorts
                    // and the keyword search match on what is displayed.
                    scope_text: groupScopeText(group),
                    expiry_text: formatExpiry(first.expires_on),
                    group,
                };
            }),
        [assignmentsQuery.data]
    );

    const filteredRows = useMemo<PermissionRow[]>(() => {
        const term = search.trim().toLowerCase();
        // Below the minimum the search is not applied at all, so a half-typed
        // word does not empty the table.
        if (term.length < MINIMUM_SEARCH_STR_LEN) {
            return tableRows;
        }
        return tableRows.filter((row) =>
            [
                row.username,
                row.email,
                row.full_name,
                // Both, so searching the description or the code finds the row -
                // the code is still what an application authorises on.
                row.role_label,
                row.role_name,
                // Both, for the same reason as the role above: the label is
                // what the row shows, the code is what someone used to typing
                // BCEID will reach for.
                row.domain,
                domainLabel(row.domain),
                // Scope was never searchable, which was tolerable when it was
                // one quiet column. It is chips now, and a district code is the
                // obvious thing to look for.
                row.scope_text,
                /*
                    And the codes behind them. A region chip reads as its name
                    now, but the code is what the grant was made against and
                    what somebody arrives with from a ticket - searching
                    KOOTENAY_BOUNDARY must still find the row its chip calls
                    Kootenay-Boundary.
                */
                ...(row.scopes ?? []).map((scope) => scope.value),
                // Searchable as displayed, so "Expired" finds every lapsed
                // grant - which is the one thing somebody scans this column for.
                row.expiry_text,
            ]
                .filter(Boolean)
                .some((field) => String(field).toLowerCase().includes(term))
        );
    }, [search, tableRows]);

    // Carbon's Pagination is 1-indexed and does not slice for us.
    const pagedRows = useMemo(
        () => filteredRows.slice((page - 1) * pageSize, page * pageSize),
        [filteredRows, page, pageSize]
    );

    const byId = useMemo(
        () => new Map(pagedRows.map((row) => [row.id, row])),
        [pagedRows]
    );

    const handleSearchChange = (value: string) => {
        setSearch(value);
        // Back to the first page: filtering to four rows while sitting on page
        // six shows an empty table that looks like no results.
        setPage(1);
        const term = value.trim();
        setSearchError(
            term.length > 0 && term.length < MINIMUM_SEARCH_STR_LEN
                ? `Keyword must have at least ${MINIMUM_SEARCH_STR_LEN} characters`
                : null
        );
    };

    /**
     * The audit trail for one user in this application.
     *
     * Keyed on the GUID, which the row carries precisely because the displayed
     * username is not a stable identifier.
     */
    const goToHistory = (row: PermissionRow) => {
        const params = new URLSearchParams({
            targetUserGuid: row.user_guid ?? "",
            // The audit keys the target by <TYPE>\<GUID>, so the directory has
            // to travel with the GUID.
            targetUserType:
                row.domain === "BCEID" ? UserType.BceidBus : UserType.Idir,
            integrationId: String(integrationId),
            environment,
            userName: row.username,
        });
        navigate(`${ROUTES.permissionHistory}?${params.toString()}`);
    };

    /**
     * Opens the edit screen for one row.
     *
     * <p>Carries the group's own key rather than a row index: the table is
     * paginated, sorted and grouped, so a position means nothing on the far
     * side of a page load. These three identify the same grant on arrival.
     */
    const goToEdit = (row: PermissionRow) => {
        const params = new URLSearchParams({
            integrationId: String(integrationId),
            environment,
            userGuid: row.user_guid ?? row.username,
            roleName: row.role_name,
            expiresOn: row.expires_on ?? "",
        });
        navigate(`${ROUTES.editAppPermission}?${params.toString()}`);
    };

    const revokeMutation = useMutation({
        /*
            One call per assignment behind the row.

            A row is a role, not an assignment: a Viewer granted for three
            regions is one row and three assignments in CSS, and removing the
            role means removing all three. Sequential rather than concurrent,
            because they are separate calls against the same user and a partial
            failure should stop rather than race - `mutateAsync` rejects on the
            first, leaving the rest in place and the reason on screen.
        */
        mutationFn: async (row: PermissionRow) => {
            for (const assignment of row.group.assignments) {
                await AdminMgmtApiService.cssIntegrationsApi.deleteCssUserRoleAssignment(
                    integrationId,
                    environment,
                    toRevokeRequest(assignment)
                );
            }
        },
        onSuccess: (_result, row) => {
            setRevokeError(null);
            setPendingRevoke(null);

            // Said out loud, because the only other evidence is a row vanishing
            // - which on a paginated table can happen off-screen.
            const count = row.group.assignments.length;
            permissionToast.succeeded(
                "Permission removed",
                `${row.role_label} was removed from ${row.username} in ${appName}` +
                    // Named when there is one, counted when there are several:
                    // listing six regions would push the toast over the table.
                    (count > 1
                        ? `, across ${count} scopes.`
                        : row.scope_text
                          ? ` for ${row.scope_text}.`
                          : ".")
            );

            invalidateAfterAccessChange(queryClient, integrationId, environment);
        },
        onError: (error: unknown) => {
            // The backend names the reason - a self-revoke, another organisation
            // - which is worth more than a status code. The modal stays open so
            // the message is not orphaned from what it refers to.
            setRevokeError(describeError(error, "The permission could not be removed."));
        },
    });

    useErrorToast({
        when: assignmentsQuery.isError,
        title: "Failed to load permissions from CSS",
        subtitle: "Please try again.",
        occurrence: assignmentsQuery.errorUpdatedAt,
    });

    /*
        The reason a revoke was refused - a self-revoke, another organisation -
        is the whole value of this message, so it waits to be dismissed rather
        than expiring. It used to sit above the table for that; an error toast
        waits too, without moving the rows.
    */
    useErrorToast({
        when: revokeError !== null,
        title: "The permission could not be removed",
        subtitle: revokeError ?? undefined,
        occurrence: revokeError,
    });

    if (assignmentsQuery.isLoading) {
        return (
            <div className="fam-table">
                <TableSkeleton headers={permissionsTableHeaders} />
            </div>
        );
    }

    return (
        <div className="fam-table bordered-table permissions-table">
            <DataTable rows={pagedRows} headers={HEADERS} isSortable>
                {({ rows, headers, getTableProps, getHeaderProps, getRowProps }) => (
                    <TableContainer>
                        <TableToolbar>
                            <TableToolbarContent className="permissions-table__toolbar">
                                <TableToolbarSearch
                                    id="permissions-table-search"
                                    placeholder="Search by keyword"
                                    persistent
                                    value={search}
                                    onChange={(event) =>
                                        handleSearchChange(
                                            typeof event === "string"
                                                ? event
                                                : (event?.target?.value ?? "")
                                        )
                                    }
                                    // Carbon fires onChange with no event when
                                    // the built-in clear button is used.
                                    onClear={() => handleSearchChange("")}
                                />
                                {/*
                                    Carbon's toolbar search takes no
                                    invalidText, so the complaint is drawn the
                                    way a field's own would be. It is not a
                                    failure - it is true while somebody is
                                    halfway through typing - so it stays at the
                                    box rather than becoming a toast.
                                */}
                                {searchError ? (
                                    <div
                                        className="cds--form-requirement permissions-table__search-error"
                                        role="alert"
                                    >
                                        {searchError}
                                    </div>
                                ) : null}
                                <Button
                                    kind="ghost"
                                    size="sm"
                                    className="link-icon-btn"
                                    renderIcon={Download}
                                    disabled={filteredRows.length === 0}
                                    onClick={() =>
                                        downloadPermissionsCsv(filteredRows, appName)
                                    }
                                >
                                    Download (.csv)
                                </Button>
                            </TableToolbarContent>
                        </TableToolbar>

                        <Table {...getTableProps()} size="md" useZebraStyles>
                            <TableHead>
                                <TableRow>
                                    {headers.map((header) => (
                                        <TableHeader
                                            {...getHeaderProps({
                                                header,
                                                // Nothing to sort a column of
                                                // buttons by.
                                                isSortable: header.key !== "action",
                                            })}
                                            key={header.key}
                                        >
                                            {header.header}
                                        </TableHeader>
                                    ))}
                                </TableRow>
                            </TableHead>
                            <TableBody>
                                {rows.length === 0 ? (
                                    <TableRow>
                                        <TableCell colSpan={HEADERS.length}>
                                            {/*
                                                "No user found" would be a claim
                                                about the application when in
                                                fact the list never arrived.
                                            */}
                                            {assignmentsQuery.isError
                                                ? "The permissions could not be loaded from CSS."
                                                : "No user found."}
                                        </TableCell>
                                    </TableRow>
                                ) : (
                                    rows.map((row) => {
                                        const data = byId.get(row.id);
                                        if (!data) {
                                            return null;
                                        }
                                        const isNew = isNewlyGranted(
                                            data,
                                            newlyGrantedKeys
                                        );
                                        return (
                                            <TableRow
                                                {...getRowProps({ row })}
                                                key={row.id}
                                                className={
                                                    isNew
                                                        ? "fam-table__row--new"
                                                        : undefined
                                                }
                                            >
                                                <TableCell>
                                                    <div className="nowrap-cell">
                                                        {isNew ? <NewUserTag /> : null}
                                                        <span>{data.username}</span>
                                                    </div>
                                                </TableCell>
                                                <TableCell>
                                                    {domainLabel(data.domain) ||
                                                        PLACE_HOLDER}
                                                </TableCell>
                                                <TableCell>
                                                    {data.full_name || PLACE_HOLDER}
                                                </TableCell>
                                                <TableCell>
                                                    {data.email ?? PLACE_HOLDER}
                                                </TableCell>
                                                {/*
                                                    A chip per scope. A role
                                                    scoped by a district AND a
                                                    forest client carries both,
                                                    and collapsing them to one
                                                    string would read as a single
                                                    odd value rather than two
                                                    conditions.
                                                */}
                                                <TableCell>
                                                    <Chip label={data.role_label} />
                                                </TableCell>
                                                <TableCell>
                                                    <ScopeCell group={data.group} />
                                                </TableCell>
                                                <TableCell>
                                                    {/*
                                                        The nowrap belongs to a
                                                        span inside the cell,
                                                        never to the cell. That
                                                        class is `display: flex`,
                                                        and a <td> given a flex
                                                        display stops being a
                                                        table cell at all - it
                                                        leaves the row's layout,
                                                        so it no longer takes the
                                                        row's height and shows as
                                                        a box of its own.
                                                    */}
                                                    <span className="expiry-text">
                                                        {data.expiry_text}
                                                    </span>
                                                </TableCell>
                                                <TableCell className="action-col">
                                                    {/*
                                                        Worded, like the remove
                                                        button beside them. An
                                                        icon alone is guessed
                                                        at, and a clock and a
                                                        pencil are a poor pair
                                                        to guess between - see
                                                        RemoveButton, which
                                                        settled this for the
                                                        third control in this
                                                        group.

                                                        The accessible names
                                                        stay row-specific for
                                                        the same reason they are
                                                        there: a page of rows
                                                        named only "History" is
                                                        a page of controls a
                                                        screen reader cannot
                                                        tell apart.
                                                    */}
                                                    <div className="nowrap-cell action-button-group">
                                                        <Button
                                                            kind="ghost"
                                                            size="sm"
                                                            className="row-action"
                                                            renderIcon={
                                                                RecentlyViewed
                                                            }
                                                            iconDescription={`Permission history for ${data.username}`}
                                                            aria-label={`Permission history for ${data.username}`}
                                                            title={`Permission history for ${data.username}`}
                                                            onClick={() =>
                                                                goToHistory(data)
                                                            }
                                                        >
                                                            History
                                                        </Button>
                                                        <Button
                                                            kind="ghost"
                                                            size="sm"
                                                            className="row-action"
                                                            renderIcon={Edit}
                                                            iconDescription={`Edit ${data.role_label} for ${data.username}`}
                                                            aria-label={`Edit ${data.role_label} for ${data.username}`}
                                                            title={`Edit ${data.role_label} for ${data.username}`}
                                                            onClick={() =>
                                                                goToEdit(data)
                                                            }
                                                        >
                                                            Edit
                                                        </Button>
                                                        <RemoveButton
                                                            accessible={`Remove ${data.role_label} from ${data.username}`}
                                                            disabled={
                                                                revokeMutation.isPending
                                                            }
                                                            onClick={() =>
                                                                setPendingRevoke(data)
                                                            }
                                                        />
                                                    </div>
                                                </TableCell>
                                            </TableRow>
                                        );
                                    })
                                )}
                            </TableBody>
                        </Table>
                    </TableContainer>
                )}
            </DataTable>

            <Pagination
                page={page}
                pageSize={pageSize}
                pageSizes={TABLE_ROWS_PER_PAGE}
                totalItems={filteredRows.length}
                onChange={({ page: next, pageSize: nextSize }) => {
                    setPage(next);
                    setPageSize(nextSize);
                }}
                size="md"
            />

            {/*
                Confirmed before it happens. Revoking is immediate and there is
                no undo: the assignment is gone from CSS and only the audit
                record says it existed.
            */}
            <DestructiveModal
                open={pendingRevoke !== null}
                title="Remove permission"
                confirmButtonText="Remove"
                loading={revokeMutation.isPending}
                onCancel={() => setPendingRevoke(null)}
                onConfirm={() =>
                    pendingRevoke && revokeMutation.mutate(pendingRevoke)
                }
                message={
                    pendingRevoke ? (
                        <span>
                            Are you sure you want to remove{" "}
                            <strong>{pendingRevoke.role_label}</strong> from{" "}
                            <strong>{pendingRevoke.username}</strong> in {appName}?
                            {/*
                                Every scope, spelled out. One click now removes
                                a whole role rather than one assignment - the
                                row groups them - so a confirmation naming only
                                the first would understate what it is about to
                                do. Listed rather than counted, because "3
                                scopes" does not tell somebody whether the one
                                they care about is among them.
                            */}
                            {pendingRevoke.group.combinations.length > 0 ? (
                                <>
                                    {" "}
                                    They will lose it for{" "}
                                    <strong>
                                        {pendingRevoke.group.combinations
                                            .map((combination) =>
                                                combination
                                                    .map((scope) =>
                                                        scopeChipLabel(scope)
                                                    )
                                                    .join(" + ")
                                            )
                                            .join(", ")}
                                    </strong>
                                    , immediately.
                                </>
                            ) : (
                                <> They will lose that access immediately.</>
                            )}
                        </span>
                    ) : (
                        ""
                    )
                }
            />
        </div>
    );
};

/**
 * The backend's own reason where there is one.
 *
 * <p>Kept as a named export because half the screens import it from here; the
 * reading itself lives in ApiUtils, where the response shapes are described.
 */
export const describeError = (error: unknown, fallback: string): string =>
    describeApiError(error, fallback);

export default CssPermissionsTable;
