import { Button } from "@carbon/react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { UserType, type CssRoleOptionDto } from "fam-api/model";
import { useMemo, useState, type FC, type FormEvent } from "react";
import { useNavigate } from "react-router-dom";
import { RoleMultiSelectTable } from "@/components/AddPermissions/RoleMultiSelectTable";
import { Chip } from "@/components/Chip";
import { InlineSpinner } from "@/components/InlineSpinner";
import { PageTitle } from "@/components/PageTitle";
import { UserSearch } from "@/components/Search/UserSearch";
import { StepContainer } from "@/components/StepContainer";
import { usePermissionToast } from "@/context/notification/usePermissionToast";
import { ROUTES } from "@/routes/routePaths";
import { AdminMgmtApiService } from "@/services/ApiServiceFactory";
import type { SelectedUser } from "@/types/SelectUserType";
import { invalidateAfterAccessChange } from "@/utils/QueryInvalidation";
import {
    newRoleScopeSelection,
    requiresScope,
    roleLabel,
    selectionsOverTheLimit,
    type RoleScopeSelection,
} from "@/utils/ScopeUtils";
import type { RoleOption } from "@/pages/AddAppPermission/grantUtils";
import { useErrorToast } from "@/context/notification/useErrorToast";
import {
    describeAppointmentError,
    toDelegatedAdminRequests,
    totalDelegations,
} from "@/pages/AddDelegatedAdmin/delegationUtils";
import { useGrantTarget, useGrantTargetName } from "../grantTarget";
import { hasErrors, NO_ERRORS, validateGrantForm } from "../AddAppPermission/validation";
import "./AddDelegatedAdmin.css";

/**
 * Appoint a delegated administrator.
 *
 * A screen of its own rather than a mode of the grant form, because the two
 * answer different questions with the same fields. Here the roles are the ones
 * the appointee may *hand out*, and the districts or forest clients are the ones
 * they may hand them out for - not what they are being given.
 *
 * <b>The form is revealed a step at a time.</b> Roles appear once a user is
 * chosen; the scope cards appear once a role that needs narrowing is ticked.
 * Everything was on screen at once before, which put a role table and two empty
 * scope pickers in front of somebody who had not yet said who this was for.
 *
 * One person at a time, as legacy did: appointing is rarer and more
 * consequential than granting, and the confirmation reads better naming one
 * person. Several roles at once, though - somebody trusted to hand out one role
 * is usually trusted with its neighbours, and appointing them one at a time
 * meant walking this form three times.
 */
export const AddDelegatedAdmin: FC = () => {
    const navigate = useNavigate();
    const queryClient = useQueryClient();
    const permissionToast = usePermissionToast();
    const { integrationId, environment } = useGrantTarget();
    const applicationName = useGrantTargetName();

    const [users, setUsers] = useState<SelectedUser[]>([]);
    const [domain, setDomain] = useState<UserType>(UserType.Idir);
    const [roles, setRoles] = useState<RoleScopeSelection[]>([]);
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

    /**
     * Every role the application defines.
     *
     * Not filtered to what the appointer may grant themselves: an application
     * administrator may grant everything, so the list is already everything they
     * could delegate. The backend refuses anything beyond their authority.
     */
    const roleOptions: CssRoleOptionDto[] = rolesQuery.data ?? [];


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
    const delegationTotal = totalDelegations({ domain, users, roles });

    const appointMutation = useMutation({
        mutationFn: async () => {
            const requests = toDelegatedAdminRequests({ domain, users, roles });

            // Sequential and per role, recording what happened to each. A
            // refusal on one role must not discard the delegations already made
            // for the others, which have happened in CSS and cannot be taken
            // back by throwing here.
            const failures: string[] = [];
            let appointed = 0;

            for (let index = 0; index < requests.length; index++) {
                try {
                    await AdminMgmtApiService.cssIntegrationsApi.createCssDelegatedAdmin(
                        integrationId,
                        environment,
                        requests[index]
                    );
                    appointed++;
                } catch (error) {
                    failures.push(
                        `${roleLabel(roles[index].role)}: ${describeAppointmentError(error)}`
                    );
                }
            }

            return { appointed, failures, userName: users[0]?.userId ?? "" };
        },
        onSuccess: ({ appointed, failures, userName }) => {
            // The Delegated admins tab is now stale, and it is not mounted yet -
            // the redirect is still to come.
            invalidateAfterAccessChange(queryClient, integrationId, environment);

            if (appointed === 0) {
                // Nothing landed, so there is nothing to confirm and nowhere
                // better than this screen to say why - the form is still filled
                // in.
                setSubmitError(failures.join(" "));
                return;
            }

            const roleWord = appointed === 1 ? "role" : "roles";
            if (failures.length > 0) {
                permissionToast.partiallySucceeded(
                    "Some roles were not delegated",
                    `${userName} can now grant ${appointed} ${roleWord} in ${applicationName}. ` +
                        `${failures.length} could not be delegated.`
                );
            } else {
                permissionToast.succeeded(
                    "Delegated admin added",
                    `${userName} can now grant ${appointed} ${roleWord} in ${applicationName}.`
                );
            }

            navigate(ROUTES.managePermissions);
        },
        onError: (error: unknown) => {
            // Only reached if the loop itself failed, since per-role failures
            // are captured above.
            setSubmitError(describeAppointmentError(error));
        },
    });

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

    const onSubmit = (event: FormEvent) => {
        event.preventDefault();
        setSubmitError(null);

        const found = validateGrantForm({ users, roles });
        setErrors(found);
        if (hasErrors(found)) {
            // No banner: every one of these errors is already rendered beside
            // the field it belongs to, and a second copy at the foot of the form
            // said nothing the first did not.
            return;
        }

        if (overTheLimit.length > 0) {
            setSubmitError(
                "One of the roles covers more scopes than a single delegation can carry. Narrow it before appointing."
            );
            return;
        }
        appointMutation.mutate();
    };

    return (
        <div className="add-delegated-admin-container">
            <PageTitle
                title="Add delegated admin"
                subtitle={`Let somebody grant roles in ${applicationName}`}
            />

            <form onSubmit={onSubmit}>
                <StepContainer title="Select a user" divider>
                    <UserSearch
                        environment={environment}
                        multiUserMode={false}
                        // One at a time, so a multi-select would be misleading.
                        onSelectionChange={(chosen) =>
                            setUsers(chosen.slice(0, 1))
                        }
                        onDomainChange={setDomain}
                        formError={
                            errors.users ? (
                                <span className="field-error">{errors.users}</span>
                            ) : null
                        }
                    />
                </StepContainer>

                {/*
                    Withheld until there is somebody to delegate to. The roles are
                    only meaningful as "what this person may hand out", and the
                    step reads as an unanswerable question without them.
                */}
                {users.length > 0 ? (
                    <StepContainer
                        title="Select the roles they may grant"
                        divider
                    >
                        <p className="step-note">
                            A delegated admin can grant and revoke the roles
                            chosen here, and nothing else. Pick as many as they
                            should be able to hand out.
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
                            districtTitle={"Districts they may grant it for"}
                            districtSubtitle={"They will be able to grant this role for these districts, and no others"}
                            regionTitle={"Regions they may grant it for"}
                            regionSubtitle={"They will be able to grant this role for these regions, and no others"}
                            clientTitle={"Organizations they may grant it for"}
                            clientSubtitle={"They will be able to grant this role for these organizations, and no others"}
                            countNoun={"delegation"}
                            errors={errors.perRole}
                        />

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
                    The running total, where the decision is made. A compound role
                    is delegated per district/organization pair, so the number
                    grows faster than the selections suggest.
                */}
                {delegationTotal > 0 ? (
                    <p className="delegation-total">
                        This will create <strong>{delegationTotal}</strong>{" "}
                        {delegationTotal === 1 ? "delegation" : "delegations"}.
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
                            appointMutation.isPending ? InlineSpinner : undefined
                        }
                        disabled={
                            appointMutation.isPending || overTheLimit.length > 0
                        }
                    >
                        Add delegated admin
                    </Button>
                </div>

            </form>
        </div>
    );
};

export default AddDelegatedAdmin;
