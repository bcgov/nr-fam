import { ArrowLeft } from "@carbon/icons-react";
import type { UserType } from "fam-api";
import type { FC } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
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
            {/*
                One way back, at the top, where somebody looks for it - the same
                shape nr-fsp-new uses. There were two before, a breadcrumb above
                the title and a Back button below the table, which went to the
                same place and had to be found first: the button sat past however
                many rows of history the person had just scrolled through.
            */}
            <button
                type="button"
                className="history-back"
                onClick={() => navigate(ROUTES.managePermissions)}
            >
                <ArrowLeft size={16} /> Back to Manage permissions
            </button>

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
            </div>
        </div>
    );
};

export default UserPermissionHistory;
