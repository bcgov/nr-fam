import type { ComponentType, FC, ReactNode } from "react";
import "./SectionTile.css";

/**
 * A titled panel with its actions on the same line as the heading.
 *
 * Ported from nr-fsp-new's FSP information tiles (`fsp-info__tile`), so the two
 * applications lay a tabbed detail pane out the same way: a flat bordered card,
 * an icon beside a regular-weight title, and the section's buttons sitting level
 * with that title rather than stacked above the content.
 *
 * The buttons being level with the title is the point. Right-aligned above the
 * table they read as belonging to the tab; on the header line they read as
 * belonging to the thing the heading names.
 */
type Props = {
    title: string;
    /** Decoration, not an action - it is muted and carries no label. */
    icon?: ComponentType<{ size?: number }>;
    /** Explanatory line under the header, where the section needs one. */
    description?: string;
    /** Buttons for this section, laid out level with the title. */
    actions?: ReactNode;
    children: ReactNode;
};

export const SectionTile: FC<Props> = ({
    title,
    icon: Icon,
    description,
    actions,
    children,
}) => (
    <section className="section-tile">
        <header className="section-tile__header">
            <h2 className="section-tile__title">
                {Icon ? <Icon size={20} /> : null}
                <span>{title}</span>
            </h2>
            {actions ? (
                <div className="section-tile__actions">{actions}</div>
            ) : null}
        </header>
        {description ? (
            <p className="section-tile__description">{description}</p>
        ) : null}
        {children}
    </section>
);

export default SectionTile;
