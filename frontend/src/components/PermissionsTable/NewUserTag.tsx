import { Tag } from "@carbon/react";
import type { FC } from "react";

/**
 * Marks a row a grant just created, until the screen is left.
 *
 * Green, like every other pill that reports an outcome - see Chip. It was grey,
 * which is the colour this app uses for "nothing to do" and read as though the
 * grant had been a no-op.
 */
export const NewUserTag: FC = () => (
    <Tag className="fam-new-tag" type="green" size="sm">
        New
    </Tag>
);

export default NewUserTag;
