import { Download, RecentlyViewed, TrashCan } from "@carbon/icons-react";
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
import { useMemo, useState, type FC } from "react";
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
import { invalidateAfterAccessChange } from "@/utils/QueryInvalidation";
import { NewUserTag } from "./NewUserTag";
import {
    downloadPermissionsCsv,
    isNewlyGranted,
    permissionsTableHeaders,
    roleLabel,
    scopeText,
    toRevokeRequest,
} from "./utils";
import "./permissionsTable.css";

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
type PermissionRow = CssUserRoleRowDto & {
    /** Carbon keys rows by id and hands only the id back in the render prop. */
    id: string;
    full_name: string;
    role_label: string;
    /** Scopes joined, so the column sorts and the keyword search matches. */
    scope_text: string;
};

const HEADERS = [
    { key: "username", header: "User Name" },
    { key: "domain", header: "Domain" },
    { key: "full_name", header: "Full Name" },
    { key: "email", header: "Email" },
    { key: "scope_text", header: "Scope" },
    { key: "role_label", header: "Role" },
    { key: "action", header: "Action" },
];

const fullNameOf = (row: CssUserRoleRowDto) =>
    [row.first_name, row.last_name].filter(Boolean).join(" ");

/**
 * Unique per assignment, not per user: somebody holding three roles is three
 * rows, and a duplicate id would make Carbon render one of them and drop the
 * rest.
 */
const rowId = (row: CssUserRoleRowDto, index: number) =>
    `${row.user_guid ?? row.username}|${row.role_name}|${scopeText(row)}|${index}`;

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
            (assignmentsQuery.data ?? []).map((row, index) => ({
                ...row,
                id: rowId(row, index),
                full_name: fullNameOf(row),
                role_label: roleLabel(row),
                scope_text: scopeText(row),
            })),
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
                row.domain,
                // Scope was never searchable, which was tolerable when it was
                // one quiet column. It is chips now, and a district code is the
                // obvious thing to look for.
                row.scope_text,
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

    const revokeMutation = useMutation({
        mutationFn: (row: PermissionRow) =>
            AdminMgmtApiService.cssIntegrationsApi.deleteCssUserRoleAssignment(
                integrationId,
                environment,
                toRevokeRequest(row)
            ),
        onSuccess: (_result, row) => {
            setRevokeError(null);
            setPendingRevoke(null);

            // Said out loud, because the only other evidence is a row vanishing
            // - which on a paginated table can happen off-screen.
            const scope = scopeText(row);
            permissionToast.succeeded(
                "Permission removed",
                `${row.role_label}${scope ? ` for ${scope}` : ""} was removed from ` +
                    `${row.username} in ${appName}.`
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
                                                    {data.domain ?? PLACE_HOLDER}
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
                                                    {data.scopes?.length ? (
                                                        <span className="scope-chips">
                                                            {data.scopes.map((scope) => (
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
                                                <TableCell>
                                                    <Chip label={data.role_label} />
                                                </TableCell>
                                                <TableCell className="action-col">
                                                    <div className="nowrap-cell action-button-group">
                                                        <button
                                                            title="User permission history"
                                                            aria-label="User permission history"
                                                            className="btn btn-icon"
                                                            type="button"
                                                            onClick={() =>
                                                                goToHistory(data)
                                                            }
                                                        >
                                                            <RecentlyViewed />
                                                        </button>
                                                        <button
                                                            title="Delete user permission"
                                                            aria-label="Delete user permission"
                                                            className="btn btn-icon"
                                                            type="button"
                                                            disabled={
                                                                revokeMutation.isPending
                                                            }
                                                            onClick={() =>
                                                                setPendingRevoke(data)
                                                            }
                                                        >
                                                            <TrashCan />
                                                        </button>
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
                            <strong>{pendingRevoke.role_label}</strong>
                            {pendingRevoke.scope_text ? (
                                <>
                                    {" "}
                                    for <strong>{pendingRevoke.scope_text}</strong>
                                </>
                            ) : null}{" "}
                            from <strong>{pendingRevoke.username}</strong> in{" "}
                            {appName}? They will lose that access immediately.
                        </span>
                    ) : (
                        ""
                    )
                }
            />
        </div>
    );
};

/** The backend's own reason where there is one, its status where there is not. */
export const describeError = (error: unknown, fallback: string): string => {
    const response = (
        error as { response?: { data?: { description?: string } }; message?: string }
    )?.response;
    return response?.data?.description ?? (error as Error)?.message ?? fallback;
};

export default CssPermissionsTable;
