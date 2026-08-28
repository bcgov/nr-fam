import { ArrowLeft } from "@carbon/icons-react";
import { Button } from "@carbon/react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
    UserType,
    type CssRoleOptionDto,
    type FamDistrictDto,
    type FamForestClientDto,
    type FamRegionDto,
} from "fam-api";
import { useEffect, useMemo, useState, type FC } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { ExpiryDateField } from "@/components/AddPermissions/ExpiryDateField";
import { RoleMultiSelectTable } from "@/components/AddPermissions/RoleMultiSelectTable";
import { Chip } from "@/components/Chip";
import { InlineSpinner } from "@/components/InlineSpinner";
import { PageTitle } from "@/components/PageTitle";
import { StepContainer } from "@/components/StepContainer";
import { TableSkeleton } from "@/components/TableSkeleton";
import { groupByRole } from "@/components/PermissionsTable/utils";
import { toRevokeRequest } from "@/components/PermissionsTable/utils";
import { useErrorToast } from "@/context/notification/useErrorToast";
import { usePermissionToast } from "@/context/notification/usePermissionToast";
import { AdminMgmtApiService, AppActlApiService } from "@/services/ApiServiceFactory";
import { ROUTES } from "@/routes/routePaths";
import { FOREST_CLIENT_SEARCH_MIN_LENGTH } from "@/constants/constants";
import { describeApiError } from "@/utils/ApiUtils";
import { invalidateAfterAccessChange } from "@/utils/QueryInvalidation";
import {
    roleLabel,
    toScopeSelections,
    type RoleScopeSelection,
} from "@/utils/ScopeUtils";
import { useGrantTarget, useGrantTargetName } from "@/pages/grantTarget";
import { validateGrantForm, hasErrors, NO_ERRORS } from "@/pages/AddAppPermission/validation";
import {
    diffScopes,
    forestClientNumbers,
    isNoop,
    plannedGrants,
    toSelection,
    withResolvedNames,
} from "./editUtils";
import "@/pages/AddAppPermission/AddAppPermission.css";

/**
 * Changing what one person's role is granted for.
 *
 * <p>The grant screen with its first step removed. Who this is for is not a
 * question here - it came from the row that was clicked - so the page opens on
 * the roles, and the person is stated rather than searched for.
 *
 * <p><b>It saves a difference, not a replacement.</b> Revoking everything and
 * granting the selection back would take away access the edit never mentioned,
 * and would do it for real in the window between the two: somebody changing one
 * district of six would be briefly without the other five, and permanently if
 * the grant half failed. Only what actually changed is sent.
 *
 * <p>Additions go first. If the two halves cannot both succeed, the person is
 * better left holding too much - which the table shows and somebody can act on -
 * than too little, which locks them out of work with nothing on screen to say
 * why.
 */
export const EditAppPermission: FC = () => {
    const navigate = useNavigate();
    const queryClient = useQueryClient();
    const permissionToast = usePermissionToast();
    const { integrationId, environment } = useGrantTarget();
    const applicationName = useGrantTargetName();
    const [params] = useSearchParams();

    // The group's own key - see groupByRole. Enough to find it again on a
    // refresh, which is why these travel in the URL rather than in router state.
    const userGuid = params.get("userGuid") ?? "";
    const roleName = params.get("roleName") ?? "";
    const heldExpiry = params.get("expiresOn") ?? "";

    const [roles, setRoles] = useState<RoleScopeSelection[]>([]);
    const [expiresOn, setExpiresOn] = useState(heldExpiry);
    const [errors, setErrors] = useState(NO_ERRORS);
    const [submitError, setSubmitError] = useState<string | null>(null);
    const [loaded, setLoaded] = useState(false);

    useErrorToast({
        when: submitError !== null,
        title: submitError ?? "",
        occurrence: submitError,
    });

    const assignmentsQuery = useQuery({
        queryKey: ["css-user-role-assignments", integrationId, environment],
        queryFn: () =>
            AdminMgmtApiService.cssIntegrationsApi
                .getCssUserRoleAssignments(integrationId, environment)
                .then((res) => res.data),
        refetchOnMount: true,
    });

    const rolesQuery = useQuery({
        queryKey: ["css-roles", integrationId, environment],
        queryFn: () =>
            AdminMgmtApiService.cssIntegrationsApi
                .getCssApplicationRoles(integrationId, environment)
                .then((res) => res.data),
    });

    // The same queries the pickers use, so arriving here warms them rather than
    // duplicating them - and so a district reads as its name, not its code.
    const districtsQuery = useQuery({
        queryKey: ["districts"],
        queryFn: () =>
            AppActlApiService.districtsApi.getDistricts().then((res) => res.data),
    });
    const regionsQuery = useQuery({
        queryKey: ["regions"],
        queryFn: () =>
            AppActlApiService.regionsApi.getRegions().then((res) => res.data),
    });

    /** The grant being edited, found again by the key that grouped it. */
    const group = useMemo(() => {
        const rows = (assignmentsQuery.data ?? []).filter(
            (row) =>
                (row.user_guid ?? row.username) === userGuid &&
                row.role_name === roleName &&
                (row.expires_on ?? "") === heldExpiry
        );
        return rows.length > 0 ? groupByRole(rows)[0] : null;
    }, [assignmentsQuery.data, userGuid, roleName, heldExpiry]);

    const roleOptions: CssRoleOptionDto[] = useMemo(
        () => rolesQuery.data ?? [],
        [rolesQuery.data]
    );

    /*
        What the held organisations are called.

        Districts and regions arrive as whole lists FAM already holds, so a code
        becomes a name for free. An organisation's name lives in the Forest
        Client API, which is searched a term at a time - so the numbers on this
        grant are looked up one by one, and only here, where a handful of them
        is the whole page rather than a column of a long table.

        Best effort, per number: one that cannot be resolved - the API is down,
        or the organisation has since been deactivated and the search only
        returns active ones - leaves that row reading as its number, which is
        what it did before and is still true. It must not cost the other names,
        and it must not stop the page.
    */
    const heldClientNumbers = useMemo(
        () =>
            (group ? forestClientNumbers(group) : []).filter(
                (number) => number.length >= FOREST_CLIENT_SEARCH_MIN_LENGTH
            ),
        [group]
    );

    const clientsQuery = useQuery({
        queryKey: ["forest-clients-by-number", environment, heldClientNumbers],
        enabled: heldClientNumbers.length > 0,
        queryFn: async () => {
            const found = await Promise.all(
                heldClientNumbers.map((number) =>
                    AppActlApiService.forestClientsApi
                        .autocompleteForestClients(number, environment)
                        .then((res) =>
                            res.data.find(
                                (client) =>
                                    client.forest_client_number === number
                            )
                        )
                        .catch(() => undefined)
                )
            );
            return found.filter(Boolean) as FamForestClientDto[];
        },
    });

    /*
        The three reference sets, as lookups.

        Districts and regions are whole lists FAM holds and the pickers already
        want, so arriving here warms them rather than duplicating them; the
        organisations are what the search above found.
    */
    const knownNames = useMemo(
        () => ({
            districts: new Map<string, FamDistrictDto>(
                (districtsQuery.data ?? []).map((district) => [
                    district.org_unit_code,
                    district,
                ])
            ),
            regions: new Map<string, FamRegionDto>(
                (regionsQuery.data ?? []).map((region) => [
                    region.region_code,
                    region,
                ])
            ),
            clients: new Map<string, FamForestClientDto>(
                (clientsQuery.data ?? []).map((client) => [
                    client.forest_client_number,
                    client,
                ])
            ),
        }),
        [districtsQuery.data, regionsQuery.data, clientsQuery.data]
    );

    /*
        Filled in once, from what the person already holds.

        Guarded by `loaded` rather than by a dependency list: the queries behind
        it settle at different moments, and without the guard a late-arriving
        district list would rebuild the selection and discard whatever had been
        changed in the meantime.
    */
    useEffect(() => {
        if (loaded || !group || roleOptions.length === 0) {
            return;
        }
        const role = roleOptions.find((one) => one.name === roleName);
        if (!role) {
            return;
        }
        setRoles(
            withResolvedNames(
                [
                    toSelection(
                        group,
                        role,
                        districtsQuery.data ?? [],
                        regionsQuery.data ?? []
                    ),
                ],
                knownNames
            )
        );
        setLoaded(true);
    }, [
        loaded,
        group,
        roleOptions,
        roleName,
        districtsQuery.data,
        regionsQuery.data,
        knownNames,
    ]);

    /*
        And for every list that arrives second, which is the usual way round:
        the selection is already on screen, so the names are patched onto it.

        This is why the seed above can run before the lists have settled without
        showing codes for the rest of the session - and why it does not have to
        wait for them, which would leave the page claiming the permission could
        not be found while a slow list was still retrying. withResolvedNames
        returns what it was given when nothing resolved, so this settles rather
        than looping.
    */
    useEffect(() => {
        setRoles((current) => withResolvedNames(current, knownNames));
    }, [knownNames]);

    const updateSelection = (
        name: string,
        patch: Partial<Omit<RoleScopeSelection, "role">>
    ) =>
        setRoles((current) =>
            current.map((one) =>
                one.role.name === name ? { ...one, ...patch } : one
            )
        );

    const selection = roles[0] ?? null;
    const target = group?.assignments[0] ?? null;

    const saveMutation = useMutation({
        mutationFn: async () => {
            if (!group || !selection) {
                return;
            }
            const wanted = toScopeSelections(
                selection.role,
                selection.districts,
                selection.forestClients,
                selection.regions
            );

            // One combination per grant. An unscoped role has exactly one, with
            // nothing in it.
            const combinations = expand(wanted);
            const diff = diffScopes(group, combinations);

            /*
                An expiry change moves no combination, so the diff alone says
                nothing has happened - and the form would report success over a
                date that was never applied.

                Re-granting is what changes it: a role has one expiry marker, and
                the grant path replaces any earlier one for the same role. So
                when the date has moved, every combination the person keeps is
                re-issued rather than only the new ones.
            */
            const expiryChanged = (expiresOn || "") !== heldExpiry;
            const toGrant = plannedGrants(diff, combinations, expiryChanged);

            for (const combination of toGrant) {
                await AdminMgmtApiService.cssIntegrationsApi.createCssUserRoleAssignment(
                    integrationId,
                    environment,
                    {
                        user_guid: userGuid,
                        user_type:
                            target?.domain === "BCEID"
                                ? UserType.BceidBus
                                : UserType.Idir,
                        role_name: roleName,
                        target_user_email: target?.email ?? undefined,
                        scopes: combination.map((scope) => ({
                            type: scope.type,
                            values: [scope.value],
                        })),
                        expires_on: expiresOn || undefined,
                    }
                );
            }

            for (const assignment of diff.removed) {
                await AdminMgmtApiService.cssIntegrationsApi.deleteCssUserRoleAssignment(
                    integrationId,
                    environment,
                    toRevokeRequest(assignment)
                );
            }

            return { ...diff, expiryChanged };
        },
        onSuccess: (diff) => {
            setSubmitError(null);
            invalidateAfterAccessChange(queryClient, integrationId, environment);

            const added = diff?.added.length ?? 0;
            const removed = diff?.removed.length ?? 0;
            const dateMoved = diff?.expiryChanged ?? false;
            const nothingHappened = diff ? isNoop(diff, dateMoved) : true;

            permissionToast.succeeded(
                nothingHappened ? "Nothing to change" : "Permission updated",
                nothingHappened
                    ? `${roleLabel(selection!.role)} is unchanged for ${target?.username}.`
                    : `${roleLabel(selection!.role)} for ${target?.username} in ` +
                      `${applicationName}: ` +
                      [
                          added ? `${added} added` : null,
                          removed ? `${removed} removed` : null,
                          // Said in its own right: an expiry change moves no
                          // scope, so a count of zero and zero would read as
                          // nothing having happened.
                          dateMoved
                              ? expiresOn
                                  ? `expiry set to ${expiresOn}`
                                  : "expiry removed"
                              : null,
                      ]
                          .filter(Boolean)
                          .join(", ") + "."
            );
            navigate(ROUTES.managePermissions);
        },
        onError: (error: unknown) => {
            setSubmitError(
                describeApiError(
                    error,
                    "The permission could not be updated. Nothing further was changed."
                )
            );
        },
    });

    const onSubmit = (event: React.FormEvent) => {
        event.preventDefault();
        const found = validateGrantForm({
            // The user is not in question here, so the step that would complain
            // about one is satisfied with the person this page is about.
            users: target ? [{ userId: target.username }] : [],
            roles,
            expiresOn,
        });
        setErrors(found);
        if (hasErrors(found)) {
            return;
        }
        saveMutation.mutate();
    };

    if (assignmentsQuery.isLoading || rolesQuery.isLoading) {
        return (
            <div className="add-app-permission-container">
                <TableSkeleton headers={["Role", "Scope"]} />
            </div>
        );
    }

    if (!group || !selection) {
        return (
            <div className="add-app-permission-container">
                <PageTitle
                    title="Edit permission"
                    subtitle="This permission could not be found"
                />
                <p className="step-note">
                    It may have been removed since the table was loaded.
                </p>
                <Button
                    kind="tertiary"
                    onClick={() => navigate(ROUTES.managePermissions)}
                >
                    Back to Manage permissions
                </Button>
            </div>
        );
    }

    return (
        <div className="add-app-permission-container">
            <button
                type="button"
                className="history-back"
                onClick={() => navigate(ROUTES.managePermissions)}
            >
                <ArrowLeft size={16} /> Back to Manage permissions
            </button>

            <PageTitle
                title="Edit permission"
                subtitle={`Change what ${target?.username} holds in ${applicationName}`}
            />

            <form onSubmit={onSubmit}>
                {/*
                    Who, stated rather than asked. The row that was clicked
                    already answered it, and a search box here would invite
                    changing the answer - which is a different operation.
                */}
                <StepContainer title="User" divider>
                    <div className="unscoped-summary">
                        <span className="unscoped-label">Editing:</span>
                        <Chip
                            label={`${target?.username}${
                                target?.first_name
                                    ? ` (${target.first_name} ${target.last_name ?? ""})`.trimEnd()
                                    : ""
                            }`}
                        />
                    </div>
                </StepContainer>

                <StepContainer title="Select the roles to grant">
                    {errors.roles ? (
                        <span className="field-error">{errors.roles}</span>
                    ) : null}

                    <RoleMultiSelectTable
                        roleOptions={roleOptions.filter(
                            (one) => one.name === roleName
                        )}
                        selections={roles}
                        onToggle={() => undefined}
                        environment={environment}
                        onDistrictsChange={(name, districts) =>
                            updateSelection(name, { districts })
                        }
                        onRegionsChange={(name, regions) =>
                            updateSelection(name, { regions })
                        }
                        onForestClientsChange={(name, forestClients) =>
                            updateSelection(name, { forestClients })
                        }
                        districtTitle="Districts this role is granted for"
                        districtSubtitle="Select one or more districts for this role"
                        regionTitle="Regions this role is granted for"
                        regionSubtitle="Select one or more regions for this role"
                        clientTitle="Organizations this role is granted for"
                        clientSubtitle="Add one or more organizations for this role"
                        errors={errors.perRole}
                    />
                </StepContainer>

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

                <div className="form-actions">
                    <Button
                        kind="tertiary"
                        type="button"
                        onClick={() => navigate(ROUTES.managePermissions)}
                    >
                        Cancel
                    </Button>
                    <Button
                        type="submit"
                        renderIcon={
                            saveMutation.isPending ? InlineSpinner : undefined
                        }
                        disabled={saveMutation.isPending}
                    >
                        Save changes
                    </Button>
                </div>
            </form>
        </div>
    );
};

/**
 * One entry per combination the selection describes.
 *
 * <p>The grant endpoint takes a scope selection and works out the pairings
 * itself; the diff has to know them one by one, because each pairing is its own
 * assignment in CSS and its own row to keep or remove.
 */
const expand = (
    selections: { type: string; values: string[] }[]
): { type: string; value: string }[][] => {
    if (selections.length === 0) {
        return [[]];
    }
    return selections.reduce<{ type: string; value: string }[][]>(
        (combinations, selection) =>
            combinations.flatMap((combination) =>
                selection.values.map((value) => [
                    ...combination,
                    { type: selection.type, value },
                ])
            ),
        [[]]
    );
};

export default EditAppPermission;
