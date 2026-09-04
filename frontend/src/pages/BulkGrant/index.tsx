import {
    Button,
    InlineLoading,
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
import { useEffect, useRef, useState, type FC } from "react";
import { useNavigate } from "react-router-dom";
import { Chip } from "@/components/Chip";
import DragDropFileInput from "@/components/DragDropFileInput";
import { RemoveButton } from "@/components/RemoveButton";
import { InlineSpinner } from "@/components/InlineSpinner";
import { PageTitle } from "@/components/PageTitle";
import { FIVE_MINUTES } from "@/constants/TimeUnits";
import { StepContainer } from "@/components/StepContainer";
import { PLACE_HOLDER } from "@/constants/constants";
import { useSelectedApp } from "@/context/application/useSelectedApp";
import { usePermissionToast } from "@/context/notification/usePermissionToast";
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
 * role names</b> before anything is granted - a table of codes is not something
 * anybody can check by eye, so confirming one would be theatre.
 *
 * The file names people by username. The GUID CSS provisions against is resolved
 * from it by the backend, so nobody has to transcribe one.
 *
 * Only ordinary application roles. Administrative roles are refused by the
 * backend: appointing an administrator is not granting access, and doing it by
 * upload would route around the tier rules the administrator screens apply.
 */
/*
    Far longer than the 10 second default in ApiServiceFactory, for these two
    calls only.

    Both endpoints work a row at a time and each row is several round trips to
    upstream services - a directory lookup to resolve the person, a read of what
    they already hold, and for the apply, the assignment itself. Twenty-three
    rows already runs past ten seconds.

    Timing out here does not stop any of that. The request keeps running on the
    server and the grants land; only the answer is lost, so the screen reports a
    failure for work that succeeded and invites a re-upload of rows that are
    already done. Better to wait than to be told the wrong thing.

    Re-uploading is safe - the preview marks rows the person already holds and
    apply skips them - but the person doing it has no way to know that from a
    timeout.
*/
const BULK_TIMEOUT_MS = FIVE_MINUTES;

export const BulkGrant: FC = () => {
    const navigate = useNavigate();
    const queryClient = useQueryClient();
    const { integrationId, environment } = useGrantTarget();
    const { selectedApp } = useSelectedApp();
    const permissionToast = usePermissionToast();

    const [file, setFile] = useState<File | null>(null);
    const [csv, setCsv] = useState<string>("");
    const [rows, setRows] = useState<CssBulkGrantRowDto[]>([]);
    const [uploadError, setUploadError] = useState<string | null>(null);
    /** Set once granting has run; the table then shows outcomes, not a preview. */
    const [applied, setApplied] = useState(false);

    const confirmRef = useRef<HTMLDivElement | null>(null);
    /**
     * Whether the page has already moved to the confirmation for this file.
     *
     * <p>Without it the effect below fires on every render that touches the
     * rows - including the one that replaces the preview with outcomes - and the
     * page would yank itself back down while somebody was reading.
     */
    const [broughtIntoView, setBroughtIntoView] = useState(false);

    const validRows = rows.filter((row) => row.valid);
    /*
        Three states, not two. A row the person already holds is neither going to
        be granted nor wrong - it says something that is already true, which is
        the normal shape of a file re-uploaded after a partly failed run.
    */
    const alreadyRows = rows.filter((row) => row.already_granted);
    const errorRows = rows.filter((row) => !row.valid && !row.already_granted);

    const previewMutation = useMutation({
        mutationFn: (text: string) =>
            AdminMgmtApiService.cssIntegrationsApi
                .previewCssBulkGrants(integrationId, environment, text, {
                    timeout: BULK_TIMEOUT_MS,
                })
                .then((res) => res.data),
        onSuccess: (preview) => setRows(preview.rows),
        onError: (error: unknown) => setUploadError(describeUploadError(error)),
    });

    const applyMutation = useMutation({
        mutationFn: () =>
            AdminMgmtApiService.cssIntegrationsApi
                .createCssBulkGrants(integrationId, environment, csv, {
                    timeout: BULK_TIMEOUT_MS,
                })
                .then((res) => res.data),
        onSuccess: (outcomes) => {
            setRows(outcomes);
            setApplied(true);
            // The permissions table is now stale.
            invalidateAfterAccessChange(queryClient, integrationId, environment);

            /*
                Said out loud as well as in the table.

                The table below changes from "Ready" to "Granted" row by row,
                which is easy to miss on a long file - and the rows are the only
                thing on screen that moved. The toast is the same one the single
                grant screens raise, so a grant reads the same however it was
                made.

                A warning rather than a success when some rows did not make it:
                the table names them, but a green toast over a partial result
                reads as "all done". Warnings wait to be dismissed rather than
                timing out - see NotificationProvider.
            */
            const granted = outcomes.filter((row) => row.valid).length;
            const failed = outcomes.length - granted;

            if (failed === 0) {
                permissionToast.succeeded(
                    "Permissions granted",
                    `${granted} permission(s) granted in ${applicationName}.`
                );
            } else if (granted === 0) {
                permissionToast.partiallySucceeded(
                    "Nothing was granted",
                    `None of the ${outcomes.length} row(s) could be granted - ` +
                        "each says why below."
                );
            } else {
                permissionToast.partiallySucceeded(
                    "Some permissions were not granted",
                    `${granted} of ${outcomes.length} row(s) granted in ` +
                        `${applicationName}. The rest say why below.`
                );
            }
        },
        onError: (error: unknown) => setUploadError(describeUploadError(error)),
    });

    /*
        The confirmation appears below the fold on most screens, so an upload
        that worked looked like an upload that did nothing. Moving to it is the
        answer rather than shrinking what is above: the template and the file
        area both have to stay reachable for the next file.

        Smoothly, except for anyone who has asked their system not to animate -
        a page that jumps under the cursor is precisely what that setting is
        about. scrollIntoView is optional-chained because jsdom does not
        implement it, and a component that only works in a browser is one that
        cannot be tested in the one place it is worth testing.
    */
    useEffect(() => {
        if (rows.length === 0) {
            setBroughtIntoView(false);
            return;
        }
        if (broughtIntoView) {
            return;
        }
        const reduced =
            window.matchMedia?.("(prefers-reduced-motion: reduce)").matches ??
            false;
        confirmRef.current?.scrollIntoView?.({
            behavior: reduced ? "auto" : "smooth",
            block: "start",
        });
        setBroughtIntoView(true);
    }, [rows.length, broughtIntoView]);

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

    /**
     * Drops one row from the upload.
     *
     * <p>A file is often nearly right - one person who has left, one name that
     * resolved to somebody else - and re-editing a spreadsheet to grant the
     * other forty-nine is a poor answer to that.
     *
     * <p><b>The line is blanked, not deleted.</b> The apply re-reads this text
     * rather than trusting the preview that came back through the browser, so
     * the removal has to be in the text or it would not be a removal at all.
     * Blanking keeps every other row's line number the one the table is
     * showing - the parser numbers rows by position and skips empty lines -
     * where deleting would renumber the rest of the file under the reader.
     */
    const removeRow = (lineNumber: number) => {
        setRows((current) =>
            current.filter((row) => row.line_number !== lineNumber)
        );
        setCsv((current) => {
            const lines = current.split(/\r?\n/);
            if (lineNumber >= 1 && lineNumber <= lines.length) {
                lines[lineNumber - 1] = "";
            }
            return lines.join("\n");
        });
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

    /**
     * One scope, as its own cell: the resolved name with the code beside it.
     *
     * <p>A column each rather than the three stacked in one, because the file
     * has a column each - a row that is wrong is wrong in a particular column,
     * and the table now says which.
     *
     * <p>The placeholder rather than an empty cell for a scope this row does not
     * carry: blank would read as a value that failed to resolve.
     */
    const scopeCell = (code?: string | null, name?: string | null) => {
        if (!code) {
            return <span className="no-scope">{PLACE_HOLDER}</span>;
        }
        return (
            <span className="scope-cell">
                <span>
                    {name ?? code}{" "}
                    {name ? (
                        <span className="scope-code">{`(${code})`}</span>
                    ) : null}
                </span>
            </span>
        );
    };



    return (
        <div className="bulk-grant-container">
            <PageTitle
                title="Bulk upload permissions"
                subtitle={`Grant roles to many users in ${applicationName}`}
            />

            {/*
                What to upload, above the pane where it is uploaded. It is
                reference material - read once, then skipped on every later
                visit - so it sits on the page rather than inside the upload
                pane, where it would read as a step.
            */}
            <section className="bulk-grant-template">
                <h2 className="bulk-grant-section-heading">Template</h2>
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
            </section>

            {/*
                The gray canvas runs the full width of the content area and down
                to the bottom of the page; the white cards sit on it. Ported from
                nr-fsp-new's data submission screen, which is the pattern this
                screen is meant to read as - see BulkGrant.css.
            */}
            <div className="bulk-grant-canvas">
                <div className="bulk-grant-canvas__inner">
                    <h2 className="bulk-grant-section-heading">Upload</h2>
                    <p className="bulk-grant-section-subtitle">
                        A CSV of usernames and the roles to grant them.
                    </p>

                    {/*
                        The file area alone, laid out the way FSP's data
                        submission card is: a bold field label, a helper line,
                        then the dashed drop zone. DragDropFileInput is FAM's
                        port of that very dropzone, so handing it the label and
                        helper is what makes the two screens read as one widget.

                        A whole-file refusal - empty, too many rows - is reported
                        on the input rather than in a banner of its own. It
                        always follows an upload, so there is always a file for
                        it to sit beside, and a banner as well showed the same
                        sentence twice.
                    */}
                    <div className="bulk-grant-card">
                        <DragDropFileInput
                            label="Permissions file"
                            helperText="Accepted format: CSV."
                            file={file}
                            accept={[".csv"]}
                            onSelect={(chosen) => void acceptFile(chosen)}
                            onRemove={removeFile}
                            invalid={Boolean(uploadError)}
                            invalidText={uploadError ?? undefined}
                        />

                        {/*
                            Checking a file is a lookup per row against a
                            SOAP-backed directory, so two hundred rows is a wait
                            measured in seconds. Nothing marked it: the file
                            appeared as a chip and then the screen sat still,
                            which reads as an upload that silently failed.

                            Said here rather than over the page: the rest of the
                            screen stays usable, and the words sit where the
                            person is already looking.
                        */}
                        {previewMutation.isPending ? (
                            <InlineLoading
                                className="bulk-grant-progress"
                                description={`Checking ${file?.name ?? "the file"}…`}
                            />
                        ) : null}
                    </div>

                    {rows.length > 0 ? (
                        <div className="bulk-grant-card" ref={confirmRef}>
                            <StepContainer title={applied ? "Result" : "Confirm"}>
                                <p className="step-note">
                                    {applied
                                        ? `${validRows.length} of ${rows.length} row(s) granted.`
                                        : `${validRows.length} permission(s) will be granted.` +
                                          (alreadyRows.length
                                              ? ` ${alreadyRows.length} row(s) are already granted and will be skipped.`
                                              : "") +
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
                                                    {/*
                                                        "Business", not
                                                        "Organization": this is
                                                        the BCeID user's own
                                                        company, and the column
                                                        three along is the
                                                        organisation the role is
                                                        granted for. Two columns
                                                        of that name would be a
                                                        guess every time.
                                                    */}
                                                    <TableHeader>Business</TableHeader>
                                                    <TableHeader>Role</TableHeader>
                                                    <TableHeader>District</TableHeader>
                                                    <TableHeader>Region</TableHeader>
                                                    <TableHeader>Organization</TableHeader>
                                                    <TableHeader>
                                                        {applied ? "Outcome" : "Status"}
                                                    </TableHeader>
                                                    {applied ? null : (
                                                        <TableHeader>Action</TableHeader>
                                                    )}
                                                </TableRow>
                                            </TableHead>
                                            <TableBody>
                                                {rows.map((row) => (
                                                    <TableRow key={row.line_number}>
                                                        <TableCell>
                                                            {row.line_number}
                                                        </TableCell>
                                                        {/*
                                                            The person's name, which is what
                                                            the uploader can actually check.
                                                            A row that resolved to nobody
                                                            shows the username the file
                                                            used, marked - a blank cell
                                                            would look ordinary.
                                                        */}
                                                        <TableCell>
                                                            {fullName(row) ? (
                                                                fullName(row)
                                                            ) : (
                                                                <span className="unresolved">
                                                                    {row.user_name}
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
                                                        {/*
                                                            The role as a pill,
                                                            reading as its name -
                                                            the same way it reads
                                                            in the permissions
                                                            table it is about to
                                                            appear in.

                                                            A row that will not
                                                            grant shows the code
                                                            as plain text: there
                                                            may be no such role,
                                                            so there is no name
                                                            to give it, and a
                                                            pill would dress up
                                                            something that is not
                                                            going to happen.
                                                        */}
                                                        <TableCell>
                                                            {row.valid || row.already_granted ? (
                                                                <Chip
                                                                    label={
                                                                        row.role_display_name ??
                                                                        row.role_code
                                                                    }
                                                                />
                                                            ) : (
                                                                row.role_code
                                                            )}
                                                        </TableCell>
                                                        <TableCell>
                                                            {scopeCell(
                                                                row.district,
                                                                row.district_name
                                                            )}
                                                        </TableCell>
                                                        <TableCell>
                                                            {scopeCell(
                                                                row.region,
                                                                row.region_name
                                                            )}
                                                        </TableCell>
                                                        <TableCell>
                                                            {scopeCell(
                                                                row.forest_client_number,
                                                                row.forest_client_name
                                                            )}
                                                        </TableCell>
                                                        {/*
                                                            A status is one word
                                                            and reads as a pill.
                                                            An error is a
                                                            sentence naming what
                                                            is wrong with this
                                                            row, and a pill would
                                                            either truncate it or
                                                            stretch into a
                                                            paragraph with a
                                                            border round it.
                                                        */}
                                                        <TableCell>
                                                            {row.already_granted ? (
                                                                /*
                                                                    Grey: nothing
                                                                    succeeded and
                                                                    nothing went
                                                                    wrong. The
                                                                    person has it
                                                                    already.
                                                                */
                                                                <Chip
                                                                    color="gray"
                                                                    label="Already granted"
                                                                />
                                                            ) : row.valid ? (
                                                                <Chip
                                                                    color="green"
                                                                    label={
                                                                        applied
                                                                            ? "Granted"
                                                                            : "Ready"
                                                                    }
                                                                />
                                                            ) : (
                                                                <span className="row-error">
                                                                    {row.error}
                                                                </span>
                                                            )}
                                                        </TableCell>

                                                        {/*
                                                            Only while the file
                                                            is still a proposal.
                                                            Once granting has
                                                            run this column is a
                                                            report, and removing
                                                            a line from a report
                                                            would take away the
                                                            record rather than
                                                            the access.
                                                        */}
                                                        {applied ? null : (
                                                            <TableCell className="action-col">
                                                                <RemoveButton
                                                                    accessible={`Remove line ${row.line_number}, ${row.user_name}, from this upload`}
                                                                    disabled={
                                                                        applyMutation.isPending
                                                                    }
                                                                    onClick={() =>
                                                                        removeRow(
                                                                            row.line_number
                                                                        )
                                                                    }
                                                                />
                                                            </TableCell>
                                                        )}
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
                        </div>
                    ) : null}
                </div>
            </div>
        </div>
    );
};

export default BulkGrant;
