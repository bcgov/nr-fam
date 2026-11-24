import { Checkbox, TextInput } from "@carbon/react";
import type { CssRoleOptionDto } from "fam-api";
import { useEffect, useState, type FC } from "react";
import { Modal } from "@/components/Modal";
import {
    MAX_DESCRIPTION_LENGTH,
    MAX_ROLE_NAME_LENGTH,
} from "@/pages/ManageRoles/roleUtils";
import {
    hasRoleFormErrors,
    validateRoleText,
    type RoleFormErrors,
} from "./validation";

type Props = {
    /** The role being edited. */
    role: CssRoleOptionDto;
    /** Disables the form while the change is being saved. */
    saving: boolean;
    /** Why the last save failed, shown inside the modal rather than behind it. */
    error: string | null;
    /**
     * Whether to offer renaming it in every environment at once. Only when the
     * person may manage roles in all of them - the same rule as creating one
     * everywhere.
     */
    canUpdateEverywhere: boolean;
    onSave: (roleName: string, description: string, everywhere: boolean) => void;
    onCancel: () => void;
};

/**
 * Rename a role, or change its description.
 *
 * The code is shown but not editable. It is what people hold and what
 * applications authorise on; the name and description are only what FAM shows
 * beside it, kept on sidecar roles nobody holds. So saving here changes no one's
 * access, which is also why this is an ordinary modal and not a destructive one.
 */
export const EditRoleModal: FC<Props> = ({
    role,
    saving,
    error,
    canUpdateEverywhere,
    onSave,
    onCancel,
}) => {
    const [roleName, setRoleName] = useState(role.display_name ?? "");
    const [description, setDescription] = useState(role.description ?? "");
    const [errors, setErrors] = useState<RoleFormErrors>({});
    /*
        Off by default: the screen is showing one environment, and changing the
        others is a choice to make, not a side effect of saving.
    */
    const [everywhere, setEverywhere] = useState(false);

    // Starts again from the role's current text if a different role is opened.
    useEffect(() => {
        setRoleName(role.display_name ?? "");
        setDescription(role.description ?? "");
        setErrors({});
        setEverywhere(false);
    }, [role]);

    /*
        Saving what is already there would write nothing; the button says so.
        Not when saving everywhere, though: this environment may already match
        while another does not, and bringing them into line is the point.
    */
    const unchanged =
        !everywhere &&
        roleName.trim() === (role.display_name ?? "").trim() &&
        description.trim() === (role.description ?? "").trim();

    const submit = () => {
        const found = validateRoleText(roleName, description);
        setErrors(found);
        if (!hasRoleFormErrors(found)) {
            onSave(roleName.trim(), description.trim(), everywhere);
        }
    };

    return (
        <Modal
            open
            modalHeading="Edit role"
            aria-label="Edit role"
            primaryButtonText={
                saving
                    ? "Saving…"
                    : everywhere
                      ? "Save in all environments"
                      : "Save"
            }
            secondaryButtonText="Cancel"
            primaryButtonDisabled={saving || unchanged}
            preventCloseOnClickOutside={saving}
            onRequestSubmit={submit}
            onRequestClose={onCancel}
            onSecondarySubmit={onCancel}
            size="sm"
        >
            <div className="edit-role-modal">
                <TextInput
                    id="edit-role-code"
                    labelText="Role code"
                    value={role.name}
                    readOnly
                    helperText="The value applications check for. It cannot be changed."
                />
                <TextInput
                    id="edit-role-name"
                    labelText="Role name"
                    maxLength={MAX_ROLE_NAME_LENGTH}
                    value={roleName}
                    disabled={saving}
                    onChange={(event) => setRoleName(event.target.value)}
                    invalid={Boolean(errors.roleName)}
                    invalidText={errors.roleName}
                    helperText="The short name shown on pickers and permission pills."
                />
                <TextInput
                    id="edit-role-description"
                    labelText="Description (Optional)"
                    maxCount={MAX_DESCRIPTION_LENGTH}
                    enableCounter
                    maxLength={MAX_DESCRIPTION_LENGTH}
                    value={description}
                    disabled={saving}
                    onChange={(event) => setDescription(event.target.value)}
                    invalid={Boolean(errors.description)}
                    invalidText={errors.description}
                    helperText="A sentence explaining what the role allows. Leave empty to remove it."
                />
                {canUpdateEverywhere ? (
                    <Checkbox
                        id="edit-role-everywhere"
                        labelText="Update in all environments"
                        helperText="Renames this role in every environment the application has. If the role is missing from any of them, nothing is changed."
                        checked={everywhere}
                        disabled={saving}
                        onChange={(_event, { checked }) => setEverywhere(checked)}
                    />
                ) : null}
                                {error ? (
                    <p
                        className="cds--form-requirement edit-role-modal__error"
                        role="alert"
                    >
                        {error}
                    </p>
                ) : null}
            </div>
        </Modal>
    );
};
