import { Breadcrumb, BreadcrumbItem, Button } from "@carbon/react";
import type { UserType } from "fam-api";
import type { FC } from "react";
import { Link, useNavigate, useSearchParams } from "react-router-dom";
import { PageTitle } from "@/components/PageTitle";
import { UserPermissionHistoryTable } from "@/components/UserPermissionHistoryTable";
import { ROUTES } from "@/routes/routePaths";
import "./UserPermissionHistory.css";

/**
 * One user's permission history for one application.
 *
 * Reached from the permissions table. Keyed on the user's GUID rather than their
 * name: the audit trail keeps no foreign key into any user record, so that it
 * survives the user being renamed or removed.
 */
export const UserPermissionHistory: FC = () => {
    const navigate = useNavigate();
    const [params] = useSearchParams();

    const targetUserGuid = params.get("targetUserGuid") ?? "";
    const targetUserType = (params.get("targetUserType") ?? "IDIR") as UserType;
    const integrationId = Number(params.get("integrationId"));
    const environment = params.get("environment") ?? "";
    const userName = params.get("userName") ?? "";

    return (
        <div className="user-detail-page-container">
            <Breadcrumb noTrailingSlash>
                <BreadcrumbItem>
                    <Link to={ROUTES.managePermissions}>Manage permissions</Link>
                </BreadcrumbItem>
            </Breadcrumb>

            <PageTitle
                title="Permissions History"
                subtitle={`Check ${userName}'s permission history`}
            />

            <div className="gray-container">
                <UserPermissionHistoryTable
                    targetUserGuid={targetUserGuid}
                    targetUserType={targetUserType}
                    integrationId={integrationId}
                    environment={environment}
                />

                <div className="back-button-container">
                    <Button
                        kind="secondary"
                        onClick={() => navigate(ROUTES.managePermissions)}
                    >
                        Back
                    </Button>
                </div>
            </div>
        </div>
    );
};

export default UserPermissionHistory;
