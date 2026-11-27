import { TextArea } from "@carbon/react";
import type { FC } from "react";

/**
 * The longest comment the audit trail keeps. Matches the backend's limit on
 * CssUserRoleAssignmentRequest.comment and the width of change_comment.
 */
export const MAX_PERMISSION_COMMENT_LENGTH = 500;

/**
 * Why a permission is being added, changed or removed, in the person's own words.
 *
 * <p>Optional, and stored with the audit row for the change - it is what the
 * Comment column on the permission history shows. It is not sent to CSS and
 * decides nothing about the access itself.
 */
type Props = {
    id: string;
    value: string;
    onChange: (value: string) => void;
    disabled?: boolean;
    /** Omit when the surrounding step already explains the field. */
    helperText?: string;
};

/**
 * Trimmed, and omitted when blank, so an untouched field sends nothing rather
 * than an empty string the trail would have to interpret.
 */
export const toCommentPayload = (comment: string): string | undefined =>
    comment.trim() || undefined;

export const PermissionCommentField: FC<Props> = ({
    id,
    value,
    onChange,
    disabled = false,
    helperText,
}) => (
    <TextArea
        id={id}
        className="permission-comment-field"
        labelText="Comment (optional)"
        helperText={helperText}
        rows={3}
        enableCounter
        maxCount={MAX_PERMISSION_COMMENT_LENGTH}
        value={value}
        disabled={disabled}
        onChange={(event) => onChange(event.target.value)}
    />
);

export default PermissionCommentField;
