import { Tag } from "@carbon/react";
import type { FC } from "react";

type Props = {
    label: string;
    /** Blue for a role or a scope, green where something reads as an outcome. */
    color?: "blue" | "green";
};

/**
 * A pill, as the tables use for roles and scopes.
 *
 * Carbon's Tag rather than the PrimeVue Chip it replaces. `type` carries the
 * colour, so the two hand-rolled colour classes the Vue version needed are gone.
 */
export const Chip: FC<Props> = ({ label, color = "blue" }) => (
    <Tag className="fam-chip" type={color} size="sm">
        {label}
    </Tag>
);

export default Chip;
