import type { FC } from "react";
import "./SubsectionTitle.css";

type Props = {
    title: string;
    subtitle?: string;
};

/** A heading within a step - the district picker, the organisation picker. */
export const SubsectionTitle: FC<Props> = ({ title, subtitle }) => (
    <div className="subsection-title-container">
        <h3 className="subsection-title">{title}</h3>
        {subtitle ? <p className="subsection-subtitle">{subtitle}</p> : null}
    </div>
);

export default SubsectionTitle;
