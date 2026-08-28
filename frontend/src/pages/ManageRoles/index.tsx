import {
    Button,
    Checkbox,
    ComboBox,
    Table,
    TableBody,
    TableCell,
    TableContainer,
    TableHead,
    TableHeader,
    TableRow,
    TextInput,
} from "@carbon/react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import type {
    CssRoleManagementApplicationDto,
    CssRoleBulkCreateResultDto,
    CssRoleOptionDto,
} from "fam-api";
import { useMemo, useState, type FC } from "react";
import { Chip } from "@/components/Chip";
import { InlineSpinner } from "@/components/InlineSpinner";
import { DestructiveModal } from "@/components/DestructiveModal";
import { PageTitle } from "@/components/PageTitle";
import { describeError } from "@/components/PermissionsTable/CssPermissionsTable";
import { StepContainer } from "@/components/StepContainer";
import { TableSkeleton } from "@/components/TableSkeleton";
import { SubsectionTitle } from "@/components/SubsectionTitle";
import { PLACE_HOLDER } from "@/constants/constants";
import { usePermissionToast } from "@/context/notification/usePermissionToast";
import { AdminMgmtApiService } from "@/services/ApiServiceFactory";
import { invalidateAfterRoleChange } from "@/utils/QueryInvalidation";
import { roleOptionKey, sortedByRole } from "@/utils/RoleSort";
import { useErrorToast } from "@/context/notification/useErrorToast";
import {
    applyScopeChoice,
    getDefaultFormData,
    MAX_DESCRIPTION_LENGTH,
    MAX_ROLE_NAME_LENGTH,
    toCreateRequest,
} from "@/pages/ManageRoles/roleUtils";
import {
    hasRoleFormErrors,
    validateRoleForm,
    type RoleFormErrors,
} from "./validation";
import "./ManageRoles.css";
import { RemoveButton } from "@/components/RemoveButton";
import { matchesTypedTextBeside } from "@/utils/ComboBoxFilter";

/**
 * Define the roles an application offers.
 *
 * FAM administrators only. Everywhere else in FAM decides who holds a role; this
 * decides which roles exist, which is a change to the application's own
 * authorisation model.
 *
 * A CSS role holds nothing but a name, so a role defined here becomes up to
 * three of them: the role itself named for the code, a scope marker composed
 * into it, and a sidecar carrying the description. That shape lives on the
 * backend - see `CssIntegrationService.createRole`.
 */
/** Shared with the loading skeleton so the two cannot drift. */
const ROLE_TABLE_HEADERS = [
    "Role code",
    "Name",
    "Description",
    "Scope",
    "Members",
    "Action",
];

export const ManageRoles: FC = () => {
    const queryClient = useQueryClient();
    const permissionToast = usePermissionToast();

    const [selectedApp, setSelectedApp] =
        useState<CssRoleManagementApplicationDto | null>(null);
    const [form, setForm] = useState(getDefaultFormData());
    /*
        Empty until an action is taken, not the result of validating a blank
        form. Seeding it from the defaults meant "A role code is required" and
        "A role name is required" were on screen the moment an application was
        chosen - telling somebody off for not yet having filled in a form they
        had only just been shown.
    */
    const [errors, setErrors] = useState<RoleFormErrors>({});
    const [submitError, setSubmitError] = useState<string | null>(null);

    /*
        The form's own failure - a refusal from the backend, or a step left
        undone. It waits to be dismissed rather than expiring: it is the reason
        the submit did nothing, and the form is still on screen with everything
        still filled in.
    */
    useErrorToast({
        when: submitError !== null,
        // The message is a whole sentence written for this case, so it is the
        // title - a generic one above it would only contradict it.
        title: submitError ?? "",
        occurrence: submitError,
    });

    const [created, setCreated] = useState<CssRoleOptionDto | null>(null);
    /** Set after a successful all-environments creation. */
    const [createdEverywhere, setCreatedEverywhere] =
        useState<CssRoleBulkCreateResultDto | null>(null);
    const [pendingDelete, setPendingDelete] = useState<CssRoleOptionDto | null>(
        null
    );

    /**
     * The application as the picker labels it, for wording that names it.
     *
     * Falls back to the raw name: an option with no description would otherwise
     * leave a sentence reading "deleted from undefined".
     */
    const applicationName =
        selectedApp?.description ?? "this application";

    /*
        Its own list, not the one the permissions screens use.

        That one is filtered by who may manage access, and a DevOps administrator
        manages none - so this picker was empty for exactly the people the screen
        was opened up to. This asks the question this screen is about: whose roles
        may you define. It also answers, per row, whether the caller holds every
        environment the integration has, which only the backend can know: the list
        carries only the environments they may manage.
    */
    const applicationsQuery = useQuery({
        queryKey: ["css-applications-role-management"],
        queryFn: () =>
            AdminMgmtApiService.cssIntegrationsApi
                .getCssApplicationsForRoleManagement()
                .then((res) => res.data),
        refetchOnMount: true,
    });

    /*
        Already the right list: the endpoint above returns only the applications
        this caller may define roles for, and leaves out FAM's own - its roles
        are the administrative tiers, created by appointing administrators rather
        than from this screen.
    */
    const applicationOptions = applicationsQuery.data ?? [];

    /*
        Whether to offer "create in all environments".

        That call writes to every environment the integration has, so it takes
        authority over every one. The backend answers it per row, because only it
        can: this list carries only the environments the caller may manage, so a
        DevOps administrator holding DEV alone would otherwise see one
        environment and conclude they held the integration.
    */
    const canCreateInAllEnvironments = selectedApp?.every_environment ?? false;

    /**
     * The roles the chosen application already has.
     *
     * Shown because a code cannot be reused, and finding that out from a
     * rejected submission is a poor way to learn it.
     */
    const rolesQuery = useQuery({
        queryKey: [
            "css-roles",
            selectedApp?.integration_id,
            selectedApp?.environment,
        ],
        queryFn: () =>
            AdminMgmtApiService.cssIntegrationsApi
                .getCssApplicationRoles(
                    selectedApp!.integration_id,
                    selectedApp!.environment
                )
                .then((res) => res.data),
        enabled: Boolean(selectedApp),
    });

    /** Every table that lists roles lists them the same way - see RoleSort. */
    const orderedRoles = useMemo(
        () => sortedByRole(rolesQuery.data ?? [], roleOptionKey),
        [rolesQuery.data]
    );

    /**
     * How many people hold each role.
     *
     * Its own query, because the backend needs one upstream request per role to
     * answer it. Keeping it separate means the table renders as soon as the
     * roles arrive and fills the counts in when they do, rather than waiting on
     * the slower call - and the grant screen, which shares the `css-roles`
     * query, never pays for counts it does not show.
     */
    const memberCountsQuery = useQuery({
        queryKey: [
            "css-role-member-counts",
            selectedApp?.integration_id,
            selectedApp?.environment,
        ],
        queryFn: () =>
            AdminMgmtApiService.cssIntegrationsApi
                .getCssApplicationRoleMemberCounts(
                    selectedApp!.integration_id,
                    selectedApp!.environment
                )
                .then((res) => res.data),
        enabled: Boolean(selectedApp),
    });

    const memberCounts = useMemo<Record<string, number>>(
        () =>
            Object.fromEntries(
                (memberCountsQuery.data ?? []).map((entry) => [
                    entry.role_name,
                    entry.member_count,
                ])
            ),
        [memberCountsQuery.data]
    );

    /**
     * A role nobody holds still gets a 0 rather than a blank, but only once the
     * counts have loaded: the backend omits roles with no members, so an absent
     * entry after a successful load genuinely means none.
     */
    const memberCountFor = (role: CssRoleOptionDto): number | null =>
        memberCountsQuery.isSuccess ? (memberCounts[role.name] ?? 0) : null;

    const clearNotices = () => {
        setSubmitError(null);
        setCreated(null);
        setCreatedEverywhere(null);
    };

    const createMutation = useMutation({
        mutationFn: () =>
            AdminMgmtApiService.cssIntegrationsApi
                .createCssApplicationRole(
                    selectedApp!.integration_id,
                    selectedApp!.environment,
                    toCreateRequest(form)
                )
                .then((res) => res.data),
        onSuccess: (role) => {
            setCreated(role);
            setForm(getDefaultFormData());
            // Said the same way a grant is. The line below the form already
            // names the new role, but the form has just emptied itself, and a
            // cleared form is exactly what an unsaved one looks like.
            permissionToast.succeeded(
                "Role created",
                `${role.display_name ?? role.name} was added to ${applicationName}.`
            );
            // So the role shows up here and on the grant screen without a
            // reload.
            invalidateAfterRoleChange(
                queryClient,
                selectedApp?.integration_id,
                selectedApp?.environment
            );
        },
        onError: (error: unknown) => {
            // The backend's message names the actual problem - a taken code, a
            // malformed one - so it is worth more than a generic failure line.
            setSubmitError(describeError(error, "The role could not be created."));
        },
    });

    /**
     * Defines the role in every environment the application has.
     *
     * Sends no environment: the endpoint uses the integration's own environment
     * list, so an application with only dev and test gets two rather than a
     * request for a prod that does not exist. It refuses outright if the code is
     * taken in any environment, so this either creates it everywhere or nowhere.
     */
    const createAllMutation = useMutation({
        mutationFn: () =>
            AdminMgmtApiService.cssIntegrationsApi
                .createCssApplicationRoleAllEnvironments(
                    selectedApp!.integration_id,
                    toCreateRequest(form)
                )
                .then((res) => res.data),
        onSuccess: (result) => {
            setCreatedEverywhere(result);
            setForm(getDefaultFormData());
            permissionToast.succeeded(
                "Role created in every environment",
                `${result.role_code} was added to ${result.environments?.join(", ") ?? "every environment"}.`
            );
            // Only the selected environment's listings are on screen, but the
            // role now exists in the others too.
            invalidateAfterRoleChange(
                queryClient,
                selectedApp?.integration_id,
                selectedApp?.environment
            );
        },
        onError: (error: unknown) => {
            // Names the environments that already have the code, which is the
            // whole reason the request was refused.
            setSubmitError(
                describeError(
                    error,
                    "The role could not be created in every environment."
                )
            );
        },
    });

    const deleteMutation = useMutation({
        mutationFn: (role: CssRoleOptionDto) =>
            AdminMgmtApiService.cssIntegrationsApi
                .deleteCssApplicationRole(
                    selectedApp!.integration_id,
                    selectedApp!.environment,
                    role.name
                )
                .then((res) => res.data),
        onSuccess: (result) => {
            setSubmitError(null);
            setCreated(null);
            setPendingDelete(null);

            // A toast, not a line on the page. The deletion is done and the row
            // has gone from the table below; a message that sits there until
            // something else replaces it outlives the thing it describes.
            //
            // It names the role and the application and stops. The derived
            // roles, the members who lost access and the delegations withdrawn
            // are all consequences of deleting the role, not separate outcomes -
            // counting them made a routine deletion read like an incident
            // report.
            permissionToast.succeeded(
                "Role deleted",
                `Role ${result.role_name} was deleted from ${applicationName}.`
            );

            // Both listings are now stale, and so is the grant screen's picker -
            // and so is everybody's access, because deleting a role takes the
            // derived roles, their members and any delegation naming it with it.
            invalidateAfterRoleChange(
                queryClient,
                selectedApp?.integration_id,
                selectedApp?.environment
            );
        },
        onError: (error: unknown) => {
            // The backend names what it managed to remove before failing, which
            // matters here: a deletion cannot be rolled back.
            setSubmitError(describeError(error, "The role could not be deleted."));
        },
    });

    /**
     * Validates and clears any previous notice.
     *
     * Shared by both buttons so they cannot disagree about what a valid role is.
     */
    const validateAndClear = (): boolean => {
        clearNotices();
        const found = validateRoleForm(form);
        setErrors(found);
        return !hasRoleFormErrors(found);
    };

    const descriptionLength = form.description.length;
    const isBusy = createMutation.isPending || createAllMutation.isPending;

    return (
        <div className="manage-roles-container">
            <PageTitle
                title="Manage roles"
                subtitle="Define the roles an application offers"
            />

            <div className="application-select-row">
                <div className="application-dropdown">
                    <ComboBox
                        id="application"
                        titleText="Application:"
                        placeholder="Choose an application to add a role to"
                        items={applicationOptions}
                        itemToString={(item: CssRoleManagementApplicationDto | null) =>
                            item?.description ?? ""
                        }
                        /*
                            Carbon shows the whole list otherwise. Beside the
                            selection, because Carbon leaves the chosen
                            application's name in the box and a plain filter
                            would narrow the list to it on reopening.
                        */
                        shouldFilterItem={matchesTypedTextBeside(
                            selectedApp?.description
                        )}
                        selectedItem={selectedApp}
                        onChange={({ selectedItem }) => {
                            setSelectedApp(selectedItem ?? null);
                            clearNotices();
                        }}
                        disabled={applicationsQuery.isLoading}
                        invalid={applicationsQuery.isError}
                        invalidText="Failed to load applications from CSS. Please try again."
                    />
                </div>
            </div>

            {!selectedApp ? null : (
                <>
                    <StepContainer title="Create a role" divider>
                        <div className="role-form">
                            <div className="field">
                                <TextInput
                                    id="roleCode"
                                    labelText="Role code"
                                    placeholder="FREP_ADMINISTRATOR"
                                    value={form.roleCode}
                                    onChange={(event) =>
                                        setForm((current) => ({
                                            ...current,
                                            roleCode: event.target.value,
                                        }))
                                    }
                                    invalid={Boolean(errors.roleCode)}
                                    invalidText={errors.roleCode}
                                    helperText="The value applications check for. Letters, digits and underscores."
                                />
                            </div>

                            <div className="field">
                                <TextInput
                                    id="roleName"
                                    labelText="Role name"
                                    placeholder="View All"
                                    maxLength={MAX_ROLE_NAME_LENGTH}
                                    value={form.roleName}
                                    onChange={(event) =>
                                        setForm((current) => ({
                                            ...current,
                                            roleName: event.target.value,
                                        }))
                                    }
                                    invalid={Boolean(errors.roleName)}
                                    invalidText={errors.roleName}
                                    helperText="The short name shown on pickers and permission pills."
                                />
                            </div>

                            <div className="field">
                                {/*
                                    The field is maxLength-capped, so typing
                                    simply stops at the limit with no
                                    explanation. Carbon's own counter is what
                                    turns that into something a person can see
                                    coming - 180 is not a product decision, it is
                                    what fits inside a Keycloak role name.
                                */}
                                <TextInput
                                    id="description"
                                    labelText="Description (Optional)"
                                    placeholder="Allows users to view all the FSPs but not edit"
                                    maxCount={MAX_DESCRIPTION_LENGTH}
                                    enableCounter
                                    maxLength={MAX_DESCRIPTION_LENGTH}
                                    value={form.description}
                                    onChange={(event) =>
                                        setForm((current) => ({
                                            ...current,
                                            description: event.target.value,
                                        }))
                                    }
                                    invalid={Boolean(errors.description)}
                                    invalidText={errors.description}
                                    helperText="A sentence explaining what the role allows."
                                />
                                {descriptionLength >= MAX_DESCRIPTION_LENGTH ? (
                                    <p className="description-at-limit">
                                        That is as long as a description can be.
                                    </p>
                                ) : null}
                            </div>

                            <div className="field">
                                <SubsectionTitle title="Scope" />
                                <Checkbox
                                    id="requiresDistrict"
                                    labelText="Requires a district selection"
                                    checked={form.requiresDistrict}
                                    onChange={(_event, { checked }) =>
                                        setForm((current) =>
                                            applyScopeChoice(
                                                current,
                                                "requiresDistrict",
                                                checked
                                            )
                                        )
                                    }
                                />
                                <Checkbox
                                    id="requiresRegion"
                                    labelText="Requires a region selection"
                                    checked={form.requiresRegion}
                                    onChange={(_event, { checked }) =>
                                        setForm((current) =>
                                            applyScopeChoice(
                                                current,
                                                "requiresRegion",
                                                checked
                                            )
                                        )
                                    }
                                />
                                <Checkbox
                                    id="requiresForestClient"
                                    labelText="Requires a forest client selection"
                                    checked={form.requiresForestClient}
                                    onChange={(_event, { checked }) =>
                                        setForm((current) =>
                                            applyScopeChoice(
                                                current,
                                                "requiresForestClient",
                                                checked
                                            )
                                        )
                                    }
                                />
                            </div>
                        </div>

                        <div className="form-actions">
                            <Button
                                renderIcon={
                                    createMutation.isPending
                                        ? InlineSpinner
                                        : undefined
                                }
                                disabled={isBusy}
                                onClick={() =>
                                    validateAndClear() && createMutation.mutate()
                                }
                            >
                                Create role
                            </Button>
            {/*
                                Secondary: creating in the selected environment
                                is the ordinary action, and this one writes to
                                environments the screen is not showing - which is
                                also why it is offered only to somebody who
                                administers all of them.
                            */}
                            {canCreateInAllEnvironments ? (
                                <Button
                                    kind="secondary"
                                    renderIcon={
                                        createAllMutation.isPending
                                            ? InlineSpinner
                                            : undefined
                                    }
                                    disabled={isBusy}
                                    onClick={() =>
                                        validateAndClear() &&
                                        createAllMutation.mutate()
                                    }
                                >
                                    Create in all environments
                                </Button>
                            ) : null}
                        </div>

                        {created ? (
                            <p className="created-message">
                                Created <strong>{created.name}</strong> (
                                {created.display_name}). It can be granted from
                                Manage permissions now.
                            </p>
                        ) : null}

                        {createdEverywhere ? (
                            <p className="created-message">
                                Created{" "}
                                <strong>{createdEverywhere.role_code}</strong> (
                                {createdEverywhere.description}) in{" "}
                                <strong>
                                    {createdEverywhere.environments.join(", ")}
                                </strong>
                                . Only {selectedApp.environment} is listed below.
                            </p>
                        ) : null}
                    </StepContainer>

                    <StepContainer title="Existing roles">
                        <div className="fam-table">
                            {rolesQuery.isLoading ? (
                                <TableSkeleton headers={ROLE_TABLE_HEADERS} />
                            ) : (
                            <TableContainer>
                                <Table size="md" useZebraStyles>
                                    <TableHead>
                                        <TableRow>
                                            <TableHeader>Role code</TableHeader>
                                            <TableHeader>Name</TableHeader>
                                            <TableHeader>Description</TableHeader>
                                            <TableHeader>Scope</TableHeader>
                                            <TableHeader>Members</TableHeader>
                                            <TableHeader>Action</TableHeader>
                                        </TableRow>
                                    </TableHead>
                                    <TableBody>
                                        {orderedRoles.length === 0 ? (
                                            <TableRow>
                                                <TableCell colSpan={6}>
                                                    This application has no roles
                                                    yet
                                                </TableCell>
                                            </TableRow>
                                        ) : (
                                            orderedRoles.map((role) => {
                                                const count = memberCountFor(role);
                                                return (
                                                    <TableRow key={role.name}>
                                                        <TableCell>
                                                            {role.name}
                                                        </TableCell>
                                                        <TableCell>
                                                            {role.display_name ??
                                                                PLACE_HOLDER}
                                                        </TableCell>
                                                        <TableCell>
                                                            {role.description ??
                                                                PLACE_HOLDER}
                                                        </TableCell>
                                                        {/*
                                                            Both, when the role
                                                            requires both:
                                                            showing one would
                                                            misdescribe what a
                                                            grant will ask for.
                                                        */}
                                                        <TableCell>
                                                            {!role.role_type_district &&
                                                            !role.role_type_region &&
                                                            !role.role_type_client ? (
                                                                "None"
                                                            ) : (
                                                                <span className="scope-chips">
                                                                    {role.role_type_district ? (
                                                                        <Chip label="District" />
                                                                    ) : null}
                                                                    {role.role_type_region ? (
                                                                        <Chip label="Region" />
                                                                    ) : null}
                                                                    {role.role_type_client ? (
                                                                        <Chip label="Forest client" />
                                                                    ) : null}
                                                                </span>
                                                            )}
                                                        </TableCell>
                                                        {/*
                                                            Counts people, not
                                                            grants: someone
                                                            holding a scoped role
                                                            for three districts
                                                            is one member. An em
                                                            dash rather than 0
                                                            while the counts are
                                                            still loading, so an
                                                            unknown never reads
                                                            as "nobody".
                                                        */}
                                                        <TableCell>
                                                            {count === null ? (
                                                                <span className="count-unknown">
                                                                    {PLACE_HOLDER}
                                                                </span>
                                                            ) : (
                                                                count
                                                            )}
                                                        </TableCell>
                                                        <TableCell>
                                                            <RemoveButton
                                                                accessible={`Remove ${role.name}`}
                                                                disabled={
                                                                    deleteMutation.isPending
                                                                }
                                                                onClick={() =>
                                                                    setPendingDelete(
                                                                        role
                                                                    )
                                                                }
                                                            />
                                                        </TableCell>
                                                    </TableRow>
                                                );
                                            })
                                        )}
                                    </TableBody>
                                </Table>
                            </TableContainer>
                            )}
                        </div>
                    </StepContainer>
                </>
            )}

            {/*
                Deleting a role is not only a change to the application's
                definition: it takes the role away from everyone holding it, in
                the same act and with no undo. The member count is the part an
                administrator needs before agreeing, so it is stated plainly
                rather than left to be discovered afterwards.
            */}
            <DestructiveModal
                open={pendingDelete !== null}
                title="Delete role"
                confirmButtonText="Delete"
                loading={deleteMutation.isPending}
                onCancel={() => setPendingDelete(null)}
                onConfirm={() =>
                    pendingDelete && deleteMutation.mutate(pendingDelete)
                }
                message={
                    pendingDelete ? (
                        <span>
                            Are you sure you want to delete{" "}
                            <strong>
                                {pendingDelete.display_name || pendingDelete.name}
                            </strong>{" "}
                            from {applicationName}?{" "}
                            {describeMembers(memberCountFor(pendingDelete))} This
                            cannot be undone.
                        </span>
                    ) : (
                        ""
                    )
                }
            />
        </div>
    );
};

/** Null is "still loading", which is not the same as nobody. */
const describeMembers = (count: number | null) => {
    if (count === null) {
        return "Anyone currently holding it will lose that access immediately.";
    }
    if (count === 0) {
        return "Nobody currently holds it.";
    }
    return `${count} ${count === 1 ? "person holds" : "people hold"} it and will lose that access immediately.`;
};

export default ManageRoles;
