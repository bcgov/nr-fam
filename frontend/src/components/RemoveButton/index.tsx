import { TrashCan } from "@carbon/icons-react";
import { Button } from "@carbon/react";
import type { FC } from "react";
import "./RemoveButton.css";

/**
 * The one way a row is taken away.
 *
 * <p>Every table used to end in a bare trash icon. Icons alone are guessed at -
 * and this one is the only destructive control on the screen, so guessing wrong
 * costs somebody their access. The word is there now, in red, and the icon stays
 * beside it because it is what people scan the column for.
 *
 * <p>Ghost rather than a filled danger button: there is one of these on every
 * row, and a column of solid red reads as a page full of warnings rather than a
 * page with an action on each line.
 *
 * <p><b>The accessible name is not "Remove".</b> Twenty rows of identically
 * named buttons are twenty controls a screen reader cannot tell apart, so each
 * says what it removes; {@code label} is what the eye reads, {@code accessible}
 * what is announced.
 */
type Props = {
    /** Announced in full, e.g. "Remove FREP_EDITOR from JSMITH". */
    accessible: string;
    onClick: () => void;
    disabled?: boolean;
    /**
     * Why it cannot be pressed, shown on hover.
     *
     * <p>Kept apart from {@link Props.accessible} deliberately. Putting the
     * reason in the accessible name stops the control saying what it is - a
     * screen reader then announces a sentence about identification where the
     * word "Remove" should be - and leaves nothing consistent to find it by.
     */
    disabledReason?: string;
    /** Overrides the visible word, for the rare row that removes something else. */
    label?: string;
};

export const RemoveButton: FC<Props> = ({
    accessible,
    onClick,
    disabled = false,
    disabledReason,
    label = "Remove",
}) => (
    <Button
        kind="ghost"
        size="sm"
        className="remove-button"
        renderIcon={TrashCan}
        iconDescription={accessible}
        aria-label={accessible}
        title={disabled && disabledReason ? disabledReason : accessible}
        disabled={disabled}
        onClick={onClick}
    >
        {label}
    </Button>
);

export default RemoveButton;
