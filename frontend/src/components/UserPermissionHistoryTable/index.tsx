import {
    Table,
    TableBody,
    TableCell,
    TableContainer,
    TableHead,
    TableHeader,
    TableRow,
} from "@carbon/react";
import { useQuery } from "@tanstack/react-query";
import type {
    PermissionAuditHistoryDto,
    PrivilegeDetailsScopeDto,
    UserType,
} from "fam-api";
import { PrivilegeDetailsScopeType } from "fam-api";
import { useMemo, type FC } from "react";
import { Chip } from "@/components/Chip";
import { TableSkeleton } from "@/components/TableSkeleton";
import { PLACE_HOLDER } from "@/constants/constants";
import { useErrorToast } from "@/context/notification/useErrorToast";
import {
    AdminMgmtApiService,
    AppActlApiService,
} from "@/services/ApiServiceFactory";
import { utcToLocalDateTime } from "@/utils/DateUtils";
import "./UserPermissionHistoryTable.css";

/**
 * One user's permission history for one application.
 *
 * The trail is FAM's own: CSS records who holds what, but nothing about how it
 * came to be that way, so this is the only place a grant or a revocation is
 * recorded at all.
 */
type Props = {
    targetUserGuid: string;
    /**
     * The user's directory. The audit stores the target as `IDIR\<guid>`, so the
     * GUID alone does not identify a row - the two directories number their
     * people separately.
     */
    targetUserType: UserType;
    integrationId: number;
    environment: string;
};

const HEADERS = ["Date", "Activity", "Role", "Scope", "Performed by"];

/** "Jane Smith (JSMITH)", or nothing when the system performed it. */
const performer = (row: PermissionAuditHistoryDto): string => {
    const details = row.change_performer_user_details;
    if (!details) {
        return PLACE_HOLDER;
    }
    const name = [details.first_name, details.last_name]
        .filter(Boolean)
        .join(" ");
    return name ? `${name} (${details.username})` : (details.username ?? PLACE_HOLDER);
};

/**
 * How one scope reads in the trail - the same way it reads on a permission pill,
 * see {@code scopeChipLabel}.
 *
 * <p>Prefixed, because the three are not distinguishable from their values
 * alone: a district is a code, a region is a word, an organisation is a number,
 * and a column mixing them unlabelled leaves the reader guessing which is which.
 *
 * <p>Regions show the name where the row carries one - it is written in at the
 * time of the change, so a region renamed since still reads as it did then - and
 * fall back to the code for rows written before that was recorded.
 *
 * <p>Districts and organisations show their code, as they do on a permission
 * pill: a district's full name is too long for a chip that already says
 * "District", and an organisation's number is what the grant was made against.
 */
const scopeLabel = (scope: PrivilegeDetailsScopeDto): string => {
    const value = scope.client_id ?? scope.client_name ?? "";

    switch (scope.scope_type) {
        case PrivilegeDetailsScopeType.District:
            return `District: ${value}`;
        case PrivilegeDetailsScopeType.Region:
            return `Region: ${scope.client_name ?? value}`;
        default:
            return `Organization: ${value}`;
    }
};

/**
 * The roles a change covered, each with its scopes if it had any.
 *
 * <p>Two sources for the name, in order. The row carries one, resolved when the
 * history was read; failing that the application's own role list is consulted,
 * which is the same list the grant screen uses and is usually already cached.
 * The code is the last resort, not the first.
 */
const roleLines = (
    row: PermissionAuditHistoryDto,
    namesByCode: Map<string, string>
) =>
    (row.privilege_details?.roles ?? []).map((role) => ({
        /*
            The code is the identity, so it keys the row - two roles in one
            change are two lines and the display name is not guaranteed unique.
        */
        role: role.role,
        /** What the pill shows: the name where there is one, else the code. */
        label: role.role_display_name ?? namesByCode.get(role.role) ?? role.role,
        scopes: (role.scopes ?? []).map(scopeLabel).filter(Boolean),
    }));

export const UserPermissionHistoryTable: FC<Props> = ({
    targetUserGuid,
    targetUserType,
    integrationId,
    environment,
}) => {
    /*
        The application's roles, for their names.

        Shares its key with the grant and Manage roles screens, so arriving here
        from either costs nothing. It is a fallback, not the source: the row's
        own name wins where there is one, and a failure here leaves the code
        showing rather than emptying the column.
    */
    const rolesQuery = useQuery({
        queryKey: ["css-roles", integrationId, environment],
        queryFn: () =>
            AdminMgmtApiService.cssIntegrationsApi
                .getCssApplicationRoles(integrationId, environment)
                .then((res) => res.data),
        enabled: Boolean(integrationId && environment),
    });

    const namesByCode = useMemo(
        () =>
            new Map(
                (rolesQuery.data ?? [])
                    .filter((role) => role.display_name)
                    .map((role) => [role.name, role.display_name as string])
            ),
        [rolesQuery.data]
    );
    const historyQuery = useQuery({
        queryKey: [
            "permission-audit-history",
            targetUserGuid,
            targetUserType,
            integrationId,
            environment,
        ],
        queryFn: () =>
            AppActlApiService.permissionAuditApi
                .getPermissionAuditHistoryByUserAndApplication(
                    targetUserGuid,
                    targetUserType,
                    integrationId,
                    environment
                )
                .then((res) => res.data),
        refetchOnMount: true,
    });

    /*
        errorUpdatedAt, not isError alone: a retry that fails again is a second
        failure and should say so, rather than being taken for the first one
        still being true.
    */
    useErrorToast({
        when: historyQuery.isError,
        title: "Failed to fetch the permission history",
        subtitle: "Please try again.",
        occurrence: historyQuery.errorUpdatedAt,
    });

    if (historyQuery.isFetching) {
        return (
            <TableSkeleton headers={HEADERS} />
        );
    }

    const rows = historyQuery.data ?? [];

    return (
        <div className="user-permission-table fam-table">
            <TableContainer>
                <Table size="md" useZebraStyles>
                    <TableHead>
                        <TableRow>
                            {HEADERS.map((header) => (
                                <TableHeader key={header}>{header}</TableHeader>
                            ))}
                        </TableRow>
                    </TableHead>
                    <TableBody>
                        {rows.length === 0 ? (
                            <TableRow>
                                <TableCell colSpan={HEADERS.length}>
                                    {/*
                                        A failed fetch and an empty history look
                                        identical here, and saying "none found"
                                        after a failure claims something we do
                                        not know. The toast is dismissable; this
                                        line is what is left afterwards.
                                    */}
                                    {historyQuery.isError
                                        ? "The permission history could not be loaded."
                                        : "No User Permissions History found."}
                                </TableCell>
                            </TableRow>
                        ) : (
                            rows.map((row, index) => (
                                <TableRow
                                    key={`${row.change_date}-${row.privilege_change_type_description}-${index}`}
                                >
                                    <TableCell>
                                        {utcToLocalDateTime(row.change_date)}
                                    </TableCell>
                                    <TableCell className="privilege-type-description-col">
                                        {row.privilege_change_type_description}
                                    </TableCell>
                                    <TableCell>
                                        {roleLines(row, namesByCode).map((line) => (
                                            <Chip
                                                key={line.role}
                                                label={line.label}
                                            />
                                        ))}
                                    </TableCell>
                                    <TableCell>
                                        {roleLines(row, namesByCode).map((line) => (
                                            <div
                                                key={line.role}
                                                className="history-scopes"
                                            >
                                                {line.scopes.length === 0 ? (
                                                    PLACE_HOLDER
                                                ) : (
                                                    line.scopes.map((scope) => (
                                                        <Chip
                                                            key={scope}
                                                            label={scope}
                                                        />
                                                    ))
                                                )}
                                            </div>
                                        ))}
                                    </TableCell>
                                    <TableCell>{performer(row)}</TableCell>
                                </TableRow>
                            ))
                        )}
                    </TableBody>
                </Table>
            </TableContainer>
        </div>
    );
};

export default UserPermissionHistoryTable;
