import { Button } from "@carbon/react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { UserType } from "fam-api/model";
import { useState, type FC, type FormEvent } from "react";
import { useNavigate } from "react-router-dom";
import { PageTitle } from "@/components/PageTitle";
import { InlineSpinner } from "@/components/InlineSpinner";
import { UserSearch } from "@/components/Search/UserSearch";
import { StepContainer } from "@/components/StepContainer";
import { describeError } from "@/components/PermissionsTable/CssPermissionsTable";
import { ROUTES } from "@/routes/routePaths";
import { AdminMgmtApiService } from "@/services/ApiServiceFactory";
import type { SelectedUser } from "@/types/SelectUserType";
import { invalidateAfterAccessChange } from "@/utils/QueryInvalidation";
import { useErrorToast } from "@/context/notification/useErrorToast";
import { useGrantTarget, useGrantTargetName } from "../grantTarget";
import "@/pages/AddApplicationAdmin/AddApplicationAdmin.css";

/**
 * Appoint a DevOps administrator.
 *
 * The same one-step form as appointing an application administrator, and for the
 * same reason: the authority is over the <em>application</em>, so there is no
 * role to choose and no scope to narrow.
 *
 * <p>What it hands over is different, though. A DevOps administrator decides
 * what roles the application has; they grant nobody anything. That is why this
 * screen is reachable only by a FAM administrator - the tier below cannot hand
 * out authority it does not hold.
 */
export const AddDevopsAdmin: FC = () => {
    const navigate = useNavigate();
    const queryClient = useQueryClient();
    const { integrationId, environment } = useGrantTarget();
    const applicationName = useGrantTargetName();

    const [selectedUser, setSelectedUser] = useState<SelectedUser | null>(null);
    const [domain, setDomain] = useState<UserType>(UserType.Idir);
    const [submitError, setSubmitError] = useState<string | null>(null);

    /*
        The form's own failure - a refusal from the backend, or a step left
        undone. It waits to be dismissed rather than expiring: it is the reason
        the submit did nothing, and the form is still on screen with everything
        still filled in.
    */
    useErrorToast({
        when: submitError !== null,
        title: "The application administrator could not be appointed",
        subtitle: submitError ?? undefined,
        occurrence: submitError,
    });


    const appointMutation = useMutation({
        mutationFn: () =>
            AdminMgmtApiService.cssIntegrationsApi.createCssDevopsAdmin(
                integrationId,
                environment,
                {
                    user_guid: selectedUser?.guid ?? "",
                    user_type: domain,
                }
            ),
        onSuccess: () => {
            invalidateAfterAccessChange(queryClient, integrationId, environment);
            navigate(ROUTES.managePermissions);
        },
        onError: (error: unknown) => {
            // Names the actual refusal - appointing yourself, or reaching outside
            // your own organisation - which a generic line would hide.
            setSubmitError(
                describeError(
                    error,
                    "The DevOps administrator could not be appointed."
                )
            );
        },
    });

    const onSubmit = (event: FormEvent) => {
        event.preventDefault();
        setSubmitError(null);

        if (!selectedUser?.guid) {
            setSubmitError("Choose a user before appointing.");
            return;
        }
        appointMutation.mutate();
    };


    return (
        <div className="add-application-admin-container">
            <PageTitle
                title="Add DevOps admin"
                subtitle={`Let somebody manage the roles of ${applicationName}`}
            />

            <form onSubmit={onSubmit}>
                <StepContainer title="Select a user" divider>
                    <p className="step-note">
                        A DevOps admin can define and remove the roles of this
                        application, in this environment. They cannot grant
                        anybody access to them, and they cannot appoint other
                        administrators.
                    </p>

                    {/*
                        IDIR only. This tier is authority over the application
                        itself rather than over work done in it, and the backend
                        refuses anybody else - offering the choice would be
                        offering a search whose every result is unusable.

                        One domain, so UserSearch disables the selector rather
                        than showing a list of one.
                    */}
                    <UserSearch
                        environment={environment}
                        multiUserMode={false}
                        availableDomains={[UserType.Idir]}
                        onSelectionChange={(users) =>
                            setSelectedUser(users[0] ?? null)
                        }
                        onDomainChange={setDomain}
                    />
                </StepContainer>

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
                        disabled={appointMutation.isPending}
                    >
                        Add DevOps admin
                    </Button>
                </div>

            </form>
        </div>
    );
};

export default AddDevopsAdmin;
