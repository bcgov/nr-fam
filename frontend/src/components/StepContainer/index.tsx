import type { FC, ReactNode } from "react";
import "./StepContainer.css";

type Props = {
    title?: string;
    subtitle?: string;
    /** A rule beneath the step, separating it from the next one. */
    divider?: boolean;
    children: ReactNode;
};

/**
 * One step of a multi-step form, and the spacing between steps.
 *
 * Every add-permission screen used to carry its own copy of the divider margins.
 * They live here so a new step-based form inherits the spacing instead of
 * repeating the override.
 */
export const StepContainer: FC<Props> = ({
    title,
    subtitle,
    divider,
    children,
}) => (
    <div className="step-container">
        {title ? <h2 className="step-container__title">{title}</h2> : null}
        {subtitle ? (
            <p className="step-container__subtitle">{subtitle}</p>
        ) : null}
        <div className={subtitle ? "step-content" : undefined}>{children}</div>
        {divider ? <hr className="step-container__divider" /> : null}
    </div>
);

export default StepContainer;
