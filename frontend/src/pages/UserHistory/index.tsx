import {
    ComboBox,
    Pagination,
    Table,
    TableBody,
    TableCell,
    TableContainer,
    TableHead,
    TableHeader,
    TableRow,
    TableSelectRow,
} from "@carbon/react";
import { useQuery } from "@tanstack/react-query";
import type {
    CssApplicationOptionDto,
    PermissionAuditUserDto,
} from "fam-api";
import { useMemo, useState, type FC } from "react";
import { PageTitle } from "@/components/PageTitle";
import { StepContainer } from "@/components/StepContainer";
import { TableSkeleton } from "@/components/TableSkeleton";
import { UserPermissionHistoryTable } from "@/components/UserPermissionHistoryTable";
import {
    DEFAULT_ROW_PER_PAGE,
    PLACE_HOLDER,
    TABLE_ROWS_PER_PAGE,
} from "@/constants/constants";
import { AdminMgmtApiService, AppActlApiService } from "@/services/ApiServiceFactory";
import { domainLabel } from "@/utils/UserUtils";
import { describeApiError } from "@/utils/ApiUtils";
import { matchesTypedTextBeside } from "@/utils/ComboBoxFilter";
import "./UserHistory.css";

/**
 * What has happened to people's access in one application.
 *
 * <p>Three steps, each waiting on the one above: an application, then the people
 * something has happened to in it, then one person's trail.
 *
 * <p><b>The list comes from the trail, not from CSS.</b> That difference is the
 * feature: it includes people whose access was since removed - much of the
 * reason to open a history screen at all - and leaves out anyone granted theirs
 * before FAM recorded anything.
 *
 * <p>Open to anyone who administers the application, at any tier. The trail says
 * what has happened to access they already manage, so it tells them nothing
 * about it they could not otherwise see.
 */
export const UserHistory: FC = () => {
    const [selectedApp, setSelectedApp] =
        useState<CssApplicationOptionDto | null>(null);
    const [selectedUser, setSelectedUser] =
        useState<PermissionAuditUserDto | null>(null);
    const [page, setPage] = useState(1);
    const [pageSize, setPageSize] = useState(DEFAULT_ROW_PER_PAGE);

    /*
        The applications this caller administers - the same list the permissions
        screens offer, filtered by the same rule on the backend. FAM's own is
        left out: its "users" are FAM's administrators, whose appointments are
        already history rows against the applications they administer.
    */
    const applicationsQuery = useQuery({
        queryKey: ["css-applications"],
        queryFn: () =>
            AdminMgmtApiService.cssIntegrationsApi
                .getCssApplications()
                .then((res) => res.data),
    });

    const applicationOptions = useMemo(
        () =>
            (applicationsQuery.data ?? []).filter((app) => !app.fam_application),
        [applicationsQuery.data]
    );

    const usersQuery = useQuery({
        queryKey: [
            "audit-users",
            selectedApp?.integration_id,
            selectedApp?.environment,
        ],
        enabled: Boolean(selectedApp),
        queryFn: () =>
            AppActlApiService.permissionAuditApi
                .getPermissionAuditUsersByApplication(
                    selectedApp!.integration_id,
                    selectedApp!.environment
                )
                .then((res) => res.data),
    });

    const users = usersQuery.data ?? [];

    // Carbon's Pagination is 1-indexed and does not slice for us.
    const shown = useMemo(
        () => users.slice((page - 1) * pageSize, page * pageSize),
        [users, page, pageSize]
    );

    /**
     * Takes the chosen application, and forgets who was being read.
     *
     * <p>The person belongs to the previous application's trail, so keeping them
     * selected would put one application's name over another's history - which
     * is the one thing this screen must never do.
     */
    const onApplicationChange = (app: CssApplicationOptionDto | null) => {
        setSelectedApp(app);
        setSelectedUser(null);
        setPage(1);
    };

    const fullName = (user: PermissionAuditUserDto) =>
        [user.first_name, user.last_name].filter(Boolean).join(" ");

    return (
        <div className="user-history-container">
            <PageTitle
                title="User history"
                subtitle="View history for user access in an application"
            />

            <StepContainer title="Choose an application">
                <ComboBox
                    id="user-history-application"
                    className="user-history-application"
                    /*
                        No visible label: the step above it is called "Choose an
                        application" and the placeholder says the same, so a
                        third "Application" over the box was repetition.

                        Named for a screen reader instead. Carbon applies
                        aria-label only when titleText is absent - see
                        ComboBox.js - so the two cannot both be given, and
                        dropping the label without this would leave an unnamed
                        combobox.
                    */
                    aria-label="Application"
                    placeholder="Choose an application"
                    items={applicationOptions}
                    itemToString={(item: CssApplicationOptionDto | null) =>
                        item?.description ?? item?.name ?? ""
                    }
                    // Carbon shows the whole list otherwise - see matchesTypedText.
                    shouldFilterItem={matchesTypedTextBeside(
                        selectedApp?.description ?? selectedApp?.name
                    )}
                    selectedItem={selectedApp}
                    onChange={({ selectedItem }) =>
                        onApplicationChange(selectedItem ?? null)
                    }
                    disabled={applicationsQuery.isLoading}
                    invalid={applicationsQuery.isError}
                    invalidText="Failed to load applications from CSS. Please try again."
                />
            </StepContainer>

            {selectedApp ? (
                <StepContainer
                    title="Choose a user"
                    subtitle={`Everyone with recorded history in ${selectedApp.description}, most recently changed first`}
                >
                    {usersQuery.isLoading ? (
                        <TableSkeleton headers={["User", "Username", "Domain"]} />
                    ) : usersQuery.isError ? (
                        <p className="step-note">
                            {describeApiError(
                                usersQuery.error,
                                "The history could not be read. Try again in a moment."
                            )}
                        </p>
                    ) : users.length === 0 ? (
                        /*
                            A real answer, not an empty table: nothing has been
                            recorded here, which is different from "nobody has
                            access".
                        */
                        <p className="step-note">
                            Nothing has been recorded against{" "}
                            {selectedApp.description} yet.
                        </p>
                    ) : (
                        <>
                            {/*
                                The pagination goes inside the wrapper, not
                                beside it. That wrapper clips its overflow, and
                                the dividers in the bar are drawn deliberately
                                taller than the controls they sit beside - see
                                styles/_tables.scss. Outside it they are not
                                clipped, and each one runs past the bar.
                            */}
                            <div className="fam-table bordered-table">
                                <TableContainer>
                                    <Table size="md" useZebraStyles>
                                        <TableHead>
                                            <TableRow>
                                                {/*
                                                    Empty, deliberately: the
                                                    column holds one radio per
                                                    row and there is no
                                                    select-all for a choice of
                                                    one.
                                                */}
                                                <TableHeader />
                                                <TableHeader>User</TableHeader>
                                                <TableHeader>Username</TableHeader>
                                                <TableHeader>Domain</TableHeader>
                                                <TableHeader>Email</TableHeader>
                                                <TableHeader>Last change</TableHeader>
                                            </TableRow>
                                        </TableHead>
                                        <TableBody>
                                            {shown.map((user) => {
                                                const chosen =
                                                    user.target_user_guid ===
                                                    selectedUser?.target_user_guid;
                                                return (
                                                    <TableRow
                                                        key={user.target_user_guid}
                                                        className={
                                                            chosen
                                                                ? "user-history-row--chosen"
                                                                : undefined
                                                        }
                                                        onClick={() =>
                                                            setSelectedUser(user)
                                                        }
                                                    >
                                                        {/*
                                                            One choice at a time,
                                                            so a radio rather
                                                            than a checkbox - and
                                                            Carbon's own cell, so
                                                            it is named for a
                                                            screen reader rather
                                                            than being an unlabelled
                                                            control in a column
                                                            with no heading.
                                                        */}
                                                        <TableSelectRow
                                                            radio
                                                            id={`user-history-${user.target_user_guid}`}
                                                            name="user-history-user"
                                                            ariaLabel={`Show the history of ${
                                                                user.username ??
                                                                user.target_user_guid
                                                            }`}
                                                            checked={chosen}
                                                            onSelect={() =>
                                                                setSelectedUser(user)
                                                            }
                                                        />
                                                        {/*
                                                            The name the trail
                                                            recorded, not one
                                                            resolved now: a
                                                            person removed since
                                                            still reads as they
                                                            did then, and they
                                                            are much of the
                                                            reason to be here.
                                                        */}
                                                        <TableCell>
                                                            {fullName(user) ||
                                                                PLACE_HOLDER}
                                                        </TableCell>
                                                        <TableCell>
                                                            {user.username ??
                                                                PLACE_HOLDER}
                                                        </TableCell>
                                                        <TableCell>
                                                            {domainLabel(
                                                                user.target_user_type
                                                            )}
                                                        </TableCell>
                                                        <TableCell>
                                                            {user.email ??
                                                                PLACE_HOLDER}
                                                        </TableCell>
                                                        <TableCell>
                                                            {user.last_change_date?.slice(
                                                                0,
                                                                10
                                                            ) ?? PLACE_HOLDER}
                                                        </TableCell>
                                                    </TableRow>
                                                );
                                            })}
                                        </TableBody>
                                    </Table>
                                </TableContainer>

                                <Pagination
                                    page={page}
                                    pageSize={pageSize}
                                    pageSizes={TABLE_ROWS_PER_PAGE}
                                    totalItems={users.length}
                                    onChange={({ page: next, pageSize: nextSize }) => {
                                        setPage(next);
                                        setPageSize(nextSize);
                                    }}
                                    size="md"
                                />
                            </div>
                        </>
                    )}
                </StepContainer>
            ) : null}

            {selectedApp && selectedUser ? (
                <StepContainer
                    title="History"
                    /*
                        The person as they read, not as they are keyed. The
                        username is the identifier; the name is what somebody
                        recognises - and the trail's own snapshot of it, so a
                        person renamed since still reads as they did then.

                        Falls back to the username, then to nothing recognisable
                        at all: a row whose snapshot would not read still has a
                        GUID and still has a history worth showing.
                    */
                    subtitle={`Every recorded change to ${
                        fullName(selectedUser) ||
                        selectedUser.username ||
                        "this user"
                    }'s access in ${selectedApp.description}`}
                >
                    {/*
                        The same table the per-application history screen shows,
                        given the same four things. One component, so a change to
                        how a change reads happens once.
                    */}
                    <UserPermissionHistoryTable
                        targetUserGuid={selectedUser.target_user_guid}
                        targetUserType={selectedUser.target_user_type}
                        integrationId={selectedApp.integration_id}
                        environment={selectedApp.environment}
                    />
                </StepContainer>
            ) : null}
        </div>
    );
};

export default UserHistory;
