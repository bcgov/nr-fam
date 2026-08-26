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
import type { PermissionAuditHistoryDto, UserType } from "fam-api";
import type { FC } from "react";
import { Chip } from "@/components/Chip";
import { TableSkeleton } from "@/components/TableSkeleton";
import { PLACE_HOLDER } from "@/constants/constants";
import { useErrorToast } from "@/context/notification/useErrorToast";
import { AppActlApiService } from "@/services/ApiServiceFactory";
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

const HEADERS = ["Date", "Activity", "Details", "Performed by"];

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

/** The roles a change covered, each with its scopes if it had any. */
const roleLines = (row: PermissionAuditHistoryDto) =>
    (row.privilege_details?.roles ?? []).map((role) => ({
        role: role.role,
        scopes: (role.scopes ?? [])
            .map((scope) => scope.client_id ?? scope.client_name)
            .filter(Boolean)
            .join(", "),
    }));

export const UserPermissionHistoryTable: FC<Props> = ({
    targetUserGuid,
    targetUserType,
    integrationId,
    environment,
}) => {
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
                                        {roleLines(row).map((line) => (
                                            <div
                                                key={line.role}
                                                className="permission-details-col-container"
                                            >
                                                <div className="permission-type-container">
                                                    <p>Role:</p>
                                                    <Chip label={line.role} />
                                                </div>
                                                {line.scopes ? (
                                                    <p className="scopes">
                                                        {line.scopes}
                                                    </p>
                                                ) : null}
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
