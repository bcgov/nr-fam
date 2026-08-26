import type { FC } from "react";
import "./PageTitle.css";

type Props = {
    title: string;
    subtitle?: string;
};

/**
 * The heading every screen opens with.
 *
 * An `h1` rather than the Vue version's `h5`. That one was chosen for its size
 * and cost the page its only top-level heading, so a screen reader's heading
 * list started at level five with nothing above it. The size is set in CSS,
 * which is where a size belongs.
 */
export const PageTitle: FC<Props> = ({ title, subtitle }) => (
    <div className="fam-page-title">
        <h1 className="fam-page-title__title">{title}</h1>
        {subtitle ? (
            <p className="fam-page-title__subtitle">{subtitle}</p>
        ) : null}
    </div>
);

export default PageTitle;
