import { DotMark } from "@carbon/icons-react";
import { useState, type FC } from "react";
import { roleLabel } from "@/utils/ScopeUtils";
import type { UserGrantOutcome } from "@/pages/AddAppPermission/grantUtils";
import { describeUser, failureReason } from "./utils";
import "./GrantFailureList.css";

/**
 * The users a grant did not reach, and why.
 *
 * The reason comes from the backend rather than being generalised here: it is
 * the difference between "that user is at another organisation" and "the
 * directory is down", and an administrator needs to know which they are looking
 * at.
 *
 * The role is per outcome, not per banner: one grant can name several roles and
 * they do not share a fate, so "FREP_EDITOR was not added for these users" was
 * only ever true of one of them.
 */
type Props = {
    outcomes: UserGrantOutcome[];
};

/** Beyond this the list is collapsed, so one bad batch cannot fill the screen. */
const COLLAPSE_ABOVE = 2;

export const GrantFailureList: FC<Props> = ({ outcomes }) => {
    const [isExpanded, setExpanded] = useState(false);
    const showToggle = outcomes.length > COLLAPSE_ABOVE;
    const visible =
        !showToggle || isExpanded ? outcomes : outcomes.slice(0, COLLAPSE_ABOVE);

    return (
        <div className="grant-failure-list">
            <ul>
                {visible.map((outcome) => (
                    <li key={`${outcome.user.userId}|${outcome.role.name}`}>
                        <DotMark size={16} className="grant-failure-list__dot" />
                        <span>
                            {describeUser(outcome)} - {roleLabel(outcome.role)} -{" "}
                            {failureReason(outcome)}
                        </span>
                    </li>
                ))}
            </ul>

            {showToggle ? (
                <button
                    className="grant-failure-list__toggle"
                    type="button"
                    onClick={() => setExpanded((open) => !open)}
                >
                    {isExpanded
                        ? "show less..."
                        : `show ${outcomes.length - COLLAPSE_ABOVE} more...`}
                </button>
            ) : null}
        </div>
    );
};

export default GrantFailureList;
