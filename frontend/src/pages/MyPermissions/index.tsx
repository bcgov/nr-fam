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
import type { SelfApplicationRoleDto } from "fam-api";
import type { FC } from "react";
import { PageTitle } from "@/components/PageTitle";
import { StepContainer } from "@/components/StepContainer";
import { TableSkeleton } from "@/components/TableSkeleton";
import { PLACE_HOLDER } from "@/constants/constants";
import { useErrorToast } from "@/context/notification/useErrorToast";
import {
    fetchSelfApplicationRoles,
    fetchSelfPermissions,
} from "@/services/AuthApiService";
import "./MyPermissions.css";

/**
 * What the signed-in user holds, in two parts.
 *
 * They come from different places and at different speeds, which is why they are
 * two queries rather than one:
 *
 * - what they may <b>administer</b> is on their token, so it is immediate;
 * - what they hold <b>as a user</b> of each application lives in CSS, one
 *   request per integration and environment, so it is slow enough to notice.
 *
 * Fetching them together would make the fast half wait for the slow one.
 */

/**
 * Every scope the role is held under, joined - "DCC", or "DCC, 00001018" for a
 * role scoped by both.
 */
const scopeOf = (role: SelfApplicationRoleDto): string =>
    (role.scopes ?? [])
        .map((scope) => scope.label || scope.value)
        .join(", ");

/** Shared with the loading skeletons so headers and columns cannot drift. */
const ROLE_HEADERS = [
    "Application",
    "Environment",
    "Role",
    "Description",
    "District / client",
];

const PERMISSION_HEADERS = ["Application", "Environment", "Role"];

export const MyPermissions: FC = () => {
    const permissionsQuery = useQuery({
        queryKey: ["self-permissions"],
        queryFn: fetchSelfPermissions,
        // Roles change through this very application, so a stale list is
        // confusing.
        refetchOnMount: true,
    });

    const applicationRolesQuery = useQuery({
        queryKey: ["self-application-roles"],
        queryFn: fetchSelfApplicationRoles,
        refetchOnMount: true,
    });

    const applicationRoles = applicationRolesQuery.data ?? [];
    const permissions = permissionsQuery.data ?? [];

    useErrorToast({
        when: applicationRolesQuery.isError,
        title: "Your application roles could not be loaded",
        subtitle: "Please try again.",
        occurrence: applicationRolesQuery.errorUpdatedAt,
    });

    useErrorToast({
        when: permissionsQuery.isError,
        title: "Your permissions could not be loaded",
        subtitle: "Please try again.",
        occurrence: permissionsQuery.errorUpdatedAt,
    });

    return (
        <div className="my-permissions-container">
            <PageTitle
                title="My permissions"
                subtitle="The applications you can use, and what you can administer"
            />

            <StepContainer title="Application roles" divider>
                <p className="section-note">
                    The roles you hold as a user of each application.
                </p>

                <div className="fam-table">
                    {applicationRolesQuery.isLoading ? (
                        <TableSkeleton headers={ROLE_HEADERS} />
                    ) : (
                    <TableContainer>
                        <Table size="md" useZebraStyles>
                            <TableHead>
                                <TableRow>
                                    {ROLE_HEADERS.map((header) => (
                                        <TableHeader key={header}>
                                            {header}
                                        </TableHeader>
                                    ))}
                                </TableRow>
                            </TableHead>
                            <TableBody>
                                {applicationRoles.length === 0 ? (
                                    <TableRow>
                                        <TableCell colSpan={ROLE_HEADERS.length}>
                                            {/*
                                                Telling somebody they hold no
                                                roles when the list never
                                                arrived is worse than telling
                                                them nothing.
                                            */}
                                            {applicationRolesQuery.isError
                                                ? "Your application roles could not be loaded."
                                                : "You hold no roles in any application"}
                                        </TableCell>
                                    </TableRow>
                                ) : (
                                    /*
                                        A scoped role is one CSS role per district
                                        or client, so somebody granted three
                                        districts holds three roles and gets three
                                        rows - which is what they actually have.
                                    */
                                    applicationRoles.map((role, index) => (
                                        <TableRow
                                            key={`${role.application_name}-${role.environment}-${role.base_role_name}-${scopeOf(role)}-${index}`}
                                        >
                                            <TableCell>
                                                {role.application_name}
                                            </TableCell>
                                            <TableCell>
                                                {role.environment.toUpperCase()}
                                            </TableCell>
                                            <TableCell>
                                                {role.role_display_name ??
                                                    role.base_role_name}
                                            </TableCell>
                                            <TableCell>
                                                {role.role_description ?? (
                                                    <span className="not-applicable">
                                                        {PLACE_HOLDER}
                                                    </span>
                                                )}
                                            </TableCell>
                                            <TableCell>
                                                {scopeOf(role) || (
                                                    <span className="not-applicable">
                                                        {PLACE_HOLDER}
                                                    </span>
                                                )}
                                            </TableCell>
                                        </TableRow>
                                    ))
                                )}
                            </TableBody>
                        </Table>
                    </TableContainer>
                    )}
                </div>
            </StepContainer>

            <StepContainer title="Administrative permissions">
                <p className="section-note">What you can administer in FAM.</p>

                <div className="fam-table">
                    {permissionsQuery.isLoading ? (
                        <TableSkeleton headers={PERMISSION_HEADERS} />
                    ) : (
                    <TableContainer>
                        <Table size="md" useZebraStyles>
                            <TableHead>
                                <TableRow>
                                    {PERMISSION_HEADERS.map((header) => (
                                        <TableHeader key={header}>
                                            {header}
                                        </TableHeader>
                                    ))}
                                </TableRow>
                            </TableHead>
                            <TableBody>
                                {permissions.length === 0 ? (
                                    <TableRow>
                                        <TableCell
                                            colSpan={PERMISSION_HEADERS.length}
                                        >
                                            {permissionsQuery.isError
                                                ? "Your permissions could not be loaded."
                                                : "You do not administer any applications"}
                                        </TableCell>
                                    </TableRow>
                                ) : (
                                    permissions.map((permission, index) => (
                                        <TableRow
                                            key={`${permission.role}-${permission.css_integration_id ?? "all"}-${permission.environment ?? "all"}-${index}`}
                                        >
                                            <TableCell>
                                                {permission.application_name}
                                            </TableCell>
                                            {/*
                                                FAM_ADMIN names no application
                                                and no environment, because it
                                                administers every one.
                                            */}
                                            <TableCell>
                                                {permission.environment ? (
                                                    permission.environment.toUpperCase()
                                                ) : (
                                                    <span className="not-applicable">
                                                        {PLACE_HOLDER}
                                                    </span>
                                                )}
                                            </TableCell>
                                            <TableCell>
                                                {permission.role_description}
                                            </TableCell>
                                        </TableRow>
                                    ))
                                )}
                            </TableBody>
                        </Table>
                    </TableContainer>
                    )}
                </div>
            </StepContainer>
        </div>
    );
};

export default MyPermissions;
