import {
    Button,
    Table,
    TableBody,
    TableCell,
    TableContainer,
    TableHead,
    TableHeader,
    TableRow,
} from "@carbon/react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import type { CssBulkGrantRowDto } from "fam-api";
import { useState, type FC } from "react";
import { useNavigate } from "react-router-dom";
import DragDropFileInput from "@/components/DragDropFileInput";
import { InlineSpinner } from "@/components/InlineSpinner";
import { PageTitle } from "@/components/PageTitle";
import { StepContainer } from "@/components/StepContainer";
import { PLACE_HOLDER } from "@/constants/constants";
import { useSelectedApp } from "@/context/application/useSelectedApp";
import { ROUTES } from "@/routes/routePaths";
import { AdminMgmtApiService } from "@/services/ApiServiceFactory";
import { invalidateAfterAccessChange } from "@/utils/QueryInvalidation";
import {
    describeUploadError,
    downloadTemplateCsv,
    EXAMPLE_CSV,
    fullName,
} from "@/pages/BulkGrant/bulkUtils";
import { useGrantTarget } from "../grantTarget";
import "./BulkGrant.css";

/**
 * Grant roles to many users from a CSV.
 *
 * Two steps, deliberately. The upload is checked and shown back as <b>names and
 * role names</b> before anything is granted - a table of GUIDs and role codes is
 * not something anybody can check by eye, so confirming one would be theatre.
 *
 * Only ordinary application roles. Administrative roles are refused by the
 * backend: appointing an administrator is not granting access, and doing it by
 * upload would route around the tier rules the administrator screens apply.
 */
export const BulkGrant: FC = () => {
    const navigate = useNavigate();
    const queryClient = useQueryClient();
    const { integrationId, environment } = useGrantTarget();
    const { selectedApp } = useSelectedApp();

    const [file, setFile] = useState<File | null>(null);
    const [csv, setCsv] = useState<string>("");
    const [rows, setRows] = useState<CssBulkGrantRowDto[]>([]);
    const [uploadError, setUploadError] = useState<string | null>(null);
    /** Set once granting has run; the table then shows outcomes, not a preview. */
    const [applied, setApplied] = useState(false);

    const validRows = rows.filter((row) => row.valid);
    const errorRows = rows.filter((row) => !row.valid);

    const previewMutation = useMutation({
        mutationFn: (text: string) =>
            AdminMgmtApiService.cssIntegrationsApi
                .previewCssBulkGrants(integrationId, environment, text)
                .then((res) => res.data),
        onSuccess: (preview) => setRows(preview.rows),
        onError: (error: unknown) => setUploadError(describeUploadError(error)),
    });

    const applyMutation = useMutation({
        mutationFn: () =>
            AdminMgmtApiService.cssIntegrationsApi
                .createCssBulkGrants(integrationId, environment, csv)
                .then((res) => res.data),
        onSuccess: (outcomes) => {
            setRows(outcomes);
            setApplied(true);
            // The permissions table is now stale.
            invalidateAfterAccessChange(queryClient, integrationId, environment);
        },
        onError: (error: unknown) => setUploadError(describeUploadError(error)),
    });

    /**
     * Takes a chosen file, however it arrived.
     *
     * The drop zone and the OS dialog both land here, so a dropped file behaves
     * exactly like a picked one - including re-running the preview.
     */
    const acceptFile = async (chosen: File) => {
        setUploadError(null);
        setApplied(false);
        setRows([]);
        setFile(chosen);
        const text = await chosen.text();
        setCsv(text);
        previewMutation.mutate(text);
    };

    const removeFile = () => {
        setFile(null);
        setCsv("");
        setRows([]);
        setApplied(false);
        setUploadError(null);
    };

    const applicationName =
        selectedApp?.description ?? environment.toUpperCase();

    /** The scope cell: the resolved name, with the code kept beside it. */
    const scopeCell = (row: CssBulkGrantRowDto) => {
        if (!row.district && !row.region && !row.forest_client_number) {
            return <span className="no-scope">Whole application</span>;
        }
        return (
            <span className="scope-cell">
                {row.district ? (
                    <span>
                        {row.district_name ?? row.district}{" "}
                        <span className="scope-code">{`(${row.district})`}</span>
                    </span>
                ) : null}
                {row.region ? (
                    <span>
                        {row.region_name ?? row.region}{" "}
                        <span className="scope-code">{`(${row.region})`}</span>
                    </span>
                ) : null}
                {row.forest_client_number ? (
                    <span>
                        {row.forest_client_name ?? row.forest_client_number}{" "}
                        <span className="scope-code">
                            {`(${row.forest_client_number})`}
                        </span>
                    </span>
                ) : null}
            </span>
        );
    };

    return (
        <div className="bulk-grant-container">
            <PageTitle
                title="Bulk upload permissions"
                subtitle={`Grant roles to many users in ${applicationName}`}
            />

            <StepContainer title="Template" divider>
                {/*
                    A button, not an anchor: this builds a blob and hands it to
                    the browser, so there is no href to follow and nothing to
                    open in a new tab. Styled as a link because that is what it
                    behaves like to the reader.
                */}
                <p className="step-note">
                    A CSV naming the user, the role, and the scope it applies to.{" "}
                    <button
                        type="button"
                        className="template-link"
                        onClick={downloadTemplateCsv}
                    >
                        Download the template
                    </button>
                    .
                </p>
                <pre className="example">{EXAMPLE_CSV}</pre>
            </StepContainer>

            <StepContainer title="Choose a file" divider>
                {/*
                    A whole-file refusal - empty, too many rows - is reported on
                    the input rather than in a banner of its own. It always
                    follows an upload, so there is always a file for it to sit
                    beside, and a banner as well showed the same sentence twice.
                */}
                <DragDropFileInput
                    file={file}
                    accept={[".csv"]}
                    onSelect={(chosen) => void acceptFile(chosen)}
                    onRemove={removeFile}
                    invalid={Boolean(uploadError)}
                    invalidText={uploadError ?? undefined}
                />
            </StepContainer>

            {rows.length > 0 ? (
                <StepContainer title={applied ? "Result" : "Confirm"}>
                    <p className="step-note">
                        {applied
                            ? `${validRows.length} of ${rows.length} row(s) granted.`
                            : `${validRows.length} row(s) will be granted.` +
                              (errorRows.length
                                  ? ` ${errorRows.length} row(s) cannot be and will be skipped - each says why below.`
                                  : "")}
                    </p>

                    <div className="fam-table">
                        <TableContainer>
                            <Table size="md" useZebraStyles>
                                <TableHead>
                                    <TableRow>
                                        <TableHeader>Line</TableHeader>
                                        <TableHeader>User</TableHeader>
                                        <TableHeader>Username</TableHeader>
                                        <TableHeader>Domain</TableHeader>
                                        <TableHeader>Organization</TableHeader>
                                        <TableHeader>Role</TableHeader>
                                        <TableHeader>Scope</TableHeader>
                                        <TableHeader>
                                            {applied ? "Outcome" : "Status"}
                                        </TableHeader>
                                    </TableRow>
                                </TableHead>
                                <TableBody>
                                    {rows.map((row) => (
                                        <TableRow key={row.line_number}>
                                            <TableCell>
                                                {row.line_number}
                                            </TableCell>
                                            {/*
                                                The name, not the GUID. Checking
                                                a GUID by eye is what this screen
                                                exists to avoid - so a row that
                                                resolved to nobody shows the raw
                                                GUID, marked, rather than a blank
                                                cell that would look ordinary.
                                            */}
                                            <TableCell>
                                                {fullName(row) ? (
                                                    fullName(row)
                                                ) : (
                                                    <span className="unresolved">
                                                        {row.user_guid}
                                                    </span>
                                                )}
                                            </TableCell>
                                            <TableCell>
                                                {row.user_name ?? PLACE_HOLDER}
                                            </TableCell>
                                            <TableCell>
                                                {row.user_type ?? PLACE_HOLDER}
                                            </TableCell>
                                            <TableCell>
                                                {row.organization ?? PLACE_HOLDER}
                                            </TableCell>
                                            {/* The role's name, falling back to the code from the file. */}
                                            <TableCell>
                                                {row.role_display_name ??
                                                    row.role_code}
                                            </TableCell>
                                            <TableCell>{scopeCell(row)}</TableCell>
                                            <TableCell>
                                                {row.valid ? (
                                                    <span className="row-ok">
                                                        {applied
                                                            ? "Granted"
                                                            : "Ready"}
                                                    </span>
                                                ) : (
                                                    <span className="row-error">
                                                        {row.error}
                                                    </span>
                                                )}
                                            </TableCell>
                                        </TableRow>
                                    ))}
                                </TableBody>
                            </Table>
                        </TableContainer>
                    </div>

                    <div className="form-actions">
                        {applied ? (
                            <Button
                                onClick={() => navigate(ROUTES.managePermissions)}
                            >
                                Done
                            </Button>
                        ) : (
                            <>
                                <Button
                                    kind="secondary"
                                    onClick={() =>
                                        navigate(ROUTES.managePermissions)
                                    }
                                >
                                    Cancel
                                </Button>
                                <Button
                                    renderIcon={
                                        applyMutation.isPending
                                            ? InlineSpinner
                                            : undefined
                                    }
                                    disabled={
                                        validRows.length === 0 ||
                                        applyMutation.isPending
                                    }
                                    onClick={() => applyMutation.mutate()}
                                >
                                    {`Grant ${validRows.length} permission(s)`}
                                </Button>
                            </>
                        )}
                    </div>
                </StepContainer>
            ) : null}

        </div>
    );
};

export default BulkGrant;
