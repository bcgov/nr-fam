import { Button } from "@carbon/react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { UserType, type CssRoleOptionDto } from "fam-api/model";
import { useMemo, useState, type FC, type FormEvent } from "react";
import { useNavigate } from "react-router-dom";
import { ExpiryDateField } from "@/components/AddPermissions/ExpiryDateField";
import { RoleMultiSelectTable } from "@/components/AddPermissions/RoleMultiSelectTable";
import { Chip } from "@/components/Chip";
import { InlineSpinner } from "@/components/InlineSpinner";
import { PageTitle } from "@/components/PageTitle";
import { UserSearch } from "@/components/Search/UserSearch";
import { StepContainer } from "@/components/StepContainer";
import { usePermissionToast } from "@/context/notification/usePermissionToast";
import { toGrantToast } from "@/pages/ManagePermissions/utils";
import { ROUTES } from "@/routes/routePaths";
import { AdminMgmtApiService } from "@/services/ApiServiceFactory";
import type { SelectedUser } from "@/types/SelectUserType";
import { invalidateAfterAccessChange } from "@/utils/QueryInvalidation";
import { useErrorToast } from "@/context/notification/useErrorToast";
import {
    newRoleScopeSelection,
    requiresScope,
    roleLabel,
    selectionsOverTheLimit,
    type RoleScopeSelection,
} from "@/utils/ScopeUtils";
import {
    AddAppUserPermissionSuccessQuerykey,
    describeGrantError,
    planGrants,
    totalPermissions,
    type AppPermissionGrantSummary,
    type RoleOption,
    type UserGrantOutcome,
} from "@/pages/AddAppPermission/grantUtils";
import { useGrantTarget, useGrantTargetName } from "../grantTarget";
import { hasErrors, NO_ERRORS, validateGrantForm } from "./validation";
import "./AddAppPermission.css";

/**
 * Grant a CSS role to one or more users.
 *
 * An application is a CSS integration in one environment, so both identify it.
 * A grant becomes one CSS assignment request per user per role: CSS assigns to a
 * single user at a time, and a scoped grant creates one role per scope value.
 */
export const AddAppPermission: FC = () => {
    const navigate = useNavigate();
    const queryClient = useQueryClient();
    const permissionToast = usePermissionToast();
    const { integrationId, environment } = useGrantTarget();
    const applicationName = useGrantTargetName();

    const [users, setUsers] = useState<SelectedUser[]>([]);
    const [domain, setDomain] = useState<UserType>(UserType.Idir);
    const [roles, setRoles] = useState<RoleScopeSelection[]>([]);
    const [expiresOn, setExpiresOn] = useState("");
    const [errors, setErrors] = useState(NO_ERRORS);
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


    const rolesQuery = useQuery({
        queryKey: ["css-roles", integrationId, environment],
        queryFn: () =>
            AdminMgmtApiService.cssIntegrationsApi
                .getCssApplicationRoles(integrationId, environment)
                .then((res) => res.data),
        refetchOnMount: true,
    });

    const roleOptions: CssRoleOptionDto[] = rolesQuery.data ?? [];

    const grantMutation = useMutation({
        mutationFn: async (): Promise<AppPermissionGrantSummary> => {
            const planned = planGrants({ domain, users, roles, expiresOn });

            // One call per user per role. Sequential rather than concurrent:
            // each may create scope roles, and CSS treats creation as
            // find-or-create, so overlapping calls would race on the same role.
            const outcomes: UserGrantOutcome[] = [];
            for (const { user, role, request } of planned) {
                try {
                    const res =
                        await AdminMgmtApiService.cssIntegrationsApi.createCssUserRoleAssignment(
                            integrationId,
                            environment,
                            request
                        );
                    outcomes.push({ user, role, results: res.data });
                } catch (error) {
                    // Recorded and carried on with. One pair being refused - a
                    // user at another organisation, or a role they may not be
                    // given - must not discard the grants that already
                    // succeeded, which have happened in CSS and cannot be taken
                    // back by failing here.
                    outcomes.push({
                        user,
                        role,
                        results: [],
                        error: describeGrantError(error),
                    });
                }
            }

            return {
                applicationName,
                outcomes,
            };
        },
        onSuccess: (summary) => {
            invalidateAfterAccessChange(queryClient, integrationId, environment);

            // Raised before the redirect and survives it: the toast lives in
            // NotificationProvider, above the routed tree.
            const toast = toGrantToast(summary);
            if (toast) {
                const notify =
                    toast.kind === "success"
                        ? permissionToast.succeeded
                        : permissionToast.partiallySucceeded;
                notify(toast.title, toast.subtitle);
            }

            // Still left for Manage permissions to pick up: the rows to mark
            // "New", and the banner for anything that failed. Only the plain
            // success half became a toast.
            queryClient.setQueryData(
                [AddAppUserPermissionSuccessQuerykey],
                summary
            );
            navigate(ROUTES.managePermissions);
        },
        onError: (error: Error) => {
            // Only reached if the loop itself failed, since per-user failures
            // are captured above.
            setSubmitError(error.message);
        },
    });


    /**
     * The roles that need nothing further, listed under the table.
     *
     * The scoped ones say what they apply to inside their own row, so the only
     * thing left to state is which roles were granted outright.
     */
    const unscopedSelections = roles.filter(
        (selection) => !requiresScope(selection.role)
    );

    const overTheLimit = useMemo(() => selectionsOverTheLimit(roles), [roles]);
    const permissionTotal = totalPermissions({ domain, users, roles, expiresOn });

    /**
     * Ticking a role adds it; unticking drops it and everything chosen for it.
     *
     * The scope goes with the role rather than being kept in case it comes back:
     * a silently retained selection would be re-submitted by somebody who
     * thought they had cleared it.
     */
    const toggleRole = (role: RoleOption) => {
        setRoles((current) =>
            current.some((selection) => selection.role.name === role.name)
                ? current.filter(
                      (selection) => selection.role.name !== role.name
                  )
                : [...current, newRoleScopeSelection(role)]
        );
    };

    const updateSelection = (
        roleName: string,
        update: Partial<RoleScopeSelection>
    ) => {
        setRoles((current) =>
            current.map((selection) =>
                selection.role.name === roleName
                    ? { ...selection, ...update }
                    : selection
            )
        );
    };

    /**
     * Keeps the form's domain in step with the search.
     *
     * It decides the `user_type` sent on the grant, which in turn picks the
     * identity provider CSS assigns against - so a stale value grants against
     * the wrong provider, which fails verification rather than silently doing
     * nothing.
     */
    const onDomainChange = (next: UserType) => {
        setDomain(next);
        // UserSearch clears its own selection and reports the empty one before
        // this, so `users` is already reset. The roles are not - and a role
        // chosen for an IDIR user is equally valid for a BCeID one.
        setErrors(NO_ERRORS);
    };

    const onSubmit = (event: FormEvent) => {
        event.preventDefault();
        setSubmitError(null);

        const found = validateGrantForm({ users, roles, expiresOn });
        setErrors(found);
        if (hasErrors(found)) {
            // No banner: every one of these errors is already rendered beside
            // the field it belongs to, and a second copy at the foot of the form
            // said nothing the first did not.
            return;
        }

        if (overTheLimit.length > 0) {
            setSubmitError(
                "One of the roles covers more scopes than a single grant can carry. Narrow it before granting."
            );
            return;
        }
        grantMutation.mutate();
    };

    return (
        <div className="add-app-permission-container">
            <PageTitle
                title="Add permission"
                subtitle={`Grant a role to ${applicationName}`}
            />

            <form onSubmit={onSubmit}>
                <StepContainer title="Select users" divider>
                    <UserSearch
                        environment={environment}
                        multiUserMode
                        onSelectionChange={setUsers}
                        onDomainChange={onDomainChange}
                        formError={
                            errors.users ? (
                                <span className="field-error">{errors.users}</span>
                            ) : null
                        }
                    />
                </StepContainer>

                {/*
                    Nothing beyond the first step is worth showing before somebody
                    is chosen. The roles and their scope are what those people are
                    being given, so the step reads as an unanswerable question
                    without them - and it put a role table and an empty scope
                    picker in front of somebody who had not yet said who this was
                    for.
                */}
                {users.length > 0 ? (
                    <StepContainer title="Select the roles to grant">
                        <p className="step-note">
                            Everybody chosen above gets every role selected here.
                            Pick as many as they should have.
                        </p>

                        {errors.roles ? (
                            <span className="field-error">{errors.roles}</span>
                        ) : null}

                        <RoleMultiSelectTable
                            roleOptions={roleOptions}
                            selections={roles}
                            onToggle={toggleRole}
                            environment={environment}
                            onDistrictsChange={(roleName, districts) =>
                                updateSelection(roleName, { districts })
                            }
                            onRegionsChange={(roleName, regions) =>
                                updateSelection(roleName, { regions })
                            }
                            onForestClientsChange={(roleName, forestClients) =>
                                updateSelection(roleName, { forestClients })
                            }
                            districtTitle={"Districts this role is granted for"}
                            districtSubtitle={"Select one or more districts for this role"}
                            regionTitle={"Regions this role is granted for"}
                            regionSubtitle={"Select one or more regions for this role"}
                            clientTitle={"Organizations this role is granted for"}
                            clientSubtitle={"Add one or more organizations for this role"}
                            errors={errors.perRole}
                        />

                        {/*
                            The roles that need nothing further, listed here
                            rather than given an empty card below. A card with no
                            pickers in it reads as one that failed to load.
                        */}
                        {unscopedSelections.length > 0 ? (
                            <div className="unscoped-summary">
                                <span className="unscoped-label">
                                    Granted for the whole application:
                                </span>
                                {unscopedSelections.map((selection) => (
                                    <Chip
                                        key={selection.role.name}
                                        label={roleLabel(selection.role)}
                                    />
                                ))}
                            </div>
                        ) : null}
                    </StepContainer>
                ) : null}

                {/*
                    Asked once for the whole grant, not once per role: the
                    question is how long this access should last, and a
                    four-role grant should not ask it four times.
                */}
                {roles.length > 0 ? (
                    <StepContainer
                        title="Set an expiry date"
                        className="expiry-step"
                        divider
                    >
                        <ExpiryDateField
                            value={expiresOn}
                            onChange={setExpiresOn}
                            invalidText={errors.expiresOn}
                        />
                    </StepContainer>
                ) : null}

                {/*
                    Only when something actually needs narrowing. A role granted
                    outright has nothing to choose, so the step would be an empty
                    heading.
                */}

                {/*
                    The running total, where the decision is made. Every user gets
                    every role, and a compound role applies per
                    district/organization pair, so the number grows faster than
                    the selections suggest.
                */}
                {permissionTotal > 0 ? (
                    <p className="permission-total">
                        This will create <strong>{permissionTotal}</strong>{" "}
                        {permissionTotal === 1 ? "permission" : "permissions"}.
                    </p>
                ) : null}

                <div className="form-actions">
                    <Button
                        kind="secondary"
                        type="button"
                        onClick={() => navigate(ROUTES.managePermissions)}
                    >
                        Cancel
                    </Button>
                    <Button
                        type="submit"
                        renderIcon={
                            grantMutation.isPending ? InlineSpinner : undefined
                        }
                        disabled={
                            grantMutation.isPending || overTheLimit.length > 0
                        }
                    >
                        Grant permission
                    </Button>
                </div>

            </form>
        </div>
    );
};

export default AddAppPermission;
