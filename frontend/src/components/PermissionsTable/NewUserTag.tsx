import { Tag } from "@carbon/react";
import type { FC } from "react";

/** Marks a row a grant just created, until the screen is left. */
export const NewUserTag: FC = () => (
    <Tag className="fam-new-tag" type="gray" size="sm">
        New
    </Tag>
);

export default NewUserTag;
