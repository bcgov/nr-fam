import { DataTableSkeleton } from "@carbon/react";
import type { FC } from "react";

/**
 * The placeholder every table shows while it loads.
 *
 * A skeleton rather than a line of text: it occupies the space the table will,
 * so the page does not jump when the rows arrive - and FAM's tables are slow
 * enough for that to matter, since most of them fan out to CSS one request per
 * integration and environment.
 *
 * The headers come from the caller so the skeleton has the right number of
 * columns, and the toolbar and header are off because the surrounding
 * SectionTile already draws the heading.
 */
type Props = {
    headers: readonly string[];
    rowCount?: number;
};

export const TableSkeleton: FC<Props> = ({ headers, rowCount = 5 }) => (
    <DataTableSkeleton
        headers={headers.map((header) => ({ key: header, header }))}
        rowCount={rowCount}
        columnCount={headers.length}
        showToolbar={false}
        showHeader={false}
    />
);

export default TableSkeleton;
