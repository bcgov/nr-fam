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
import { AdminMgmtApiService } from "@/services/ApiServiceFactory";
import type { SelectedUser } from "@/types/SelectUserType";
import { invalidateAfterAccessChange } from "@/utils/QueryInvalidation";
import { useErrorToast } from "@/context/notification/useErrorToast";
import { anyAssigned, refusalReason } from "@/utils/AssignmentResult";
import {
    useGrantTarget,
    useGrantTargetName,
    useManagePermissionsReturn,
} from "../grantTarget";
import "./AddApplicationAdmin.css";

/**
 * Appoint an application administrator.
 *
 * Shorter than appointing a delegated administrator, and that difference is the
 * point: an application administrator is authorised over the *application*, so
 * there is no role to choose and no scope to narrow. Picking a person is the
 * whole form.
 */
export const AddApplicationAdmin: FC = () => {
    const navigate = useNavigate();
    const queryClient = useQueryClient();
    const { integrationId, environment } = useGrantTarget();
    const applicationName = useGrantTargetName();
    const returnTo = useManagePermissionsReturn();

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
            AdminMgmtApiService.cssIntegrationsApi.createCssApplicationAdmin(
                integrationId,
                environment,
                {
                    user_guid: selectedUser?.guid ?? "",
                    user_type: domain,
                }
            ),
        onSuccess: (response) => {
            // A 200 is not an appointment. CSS can refuse the assignment after
            // the role has been created, and the endpoint reports that in the
            // body rather than as a status - so the outcome has to be read, or
            // a refusal is announced as success and the person never appears in
            // the table.
            if (!anyAssigned(response.data)) {
                setSubmitError(
                    refusalReason(
                        response.data,
                        "CSS did not assign the application administrator role."
                    )
                );
                return;
            }

            invalidateAfterAccessChange(queryClient, integrationId, environment);
            navigate(returnTo);
        },
        onError: (error: unknown) => {
            // Names the actual refusal - appointing yourself, or reaching outside
            // your own organisation - which a generic line would hide.
            setSubmitError(
                describeError(
                    error,
                    "The application administrator could not be appointed."
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
                title="Add application admin"
                subtitle={`Let somebody administer ${applicationName}`}
            />

            <form onSubmit={onSubmit}>
                <StepContainer title="Select a user" divider>
                    <p className="step-note">
                        An application admin can grant and revoke every role this
                        application defines, and can appoint delegated admins for
                        it. They cannot create or delete roles.
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
                        onClick={() => navigate(returnTo)}
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
                        Add application admin
                    </Button>
                </div>

            </form>
        </div>
    );
};

export default AddApplicationAdmin;
