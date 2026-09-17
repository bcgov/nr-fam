import {
    Button,
    Checkbox,
    ComposedModal,
    ModalBody,
    ModalHeader,
    Pagination,
    RadioButton,
    Search,
    Table,
    TableBody,
    TableCell,
    TableContainer,
    TableHead,
    TableHeader,
    TableRow,
} from "@carbon/react";
import { useEffect, useMemo, useState, type FC } from "react";
import { PLACE_HOLDER } from "@/constants/constants";
import type { SelectedUser } from "@/types/SelectUserType";
import "./UserSearchResultsModal.css";

/**
 * The directory's answer, for the user to choose from.
 *
 * A modal rather than an inline list because an IDIR surname search can return
 * hundreds of people, and that does not belong in the middle of a form.
 */
type Props = {
    open: boolean;
    rows: readonly SelectedUser[];
    multiUserMode: boolean;
    onConfirm: (users: SelectedUser[]) => void;
    onCancel: () => void;
};

const PAGE_SIZES = [10, 20, 50, 100];

/** Identifies a row: the same user id can exist in both directories. */
const keyOf = (user: SelectedUser) => `${user.userId}|${user.sourceDomain}`;

/**
 * Whether to show the business column at all.
 *
 * <p>Only a Business BCeID account belongs to one, and an IDIR search is the
 * common case - a permanent column would be empty on almost every search this
 * modal ever shows. Decided over every result rather than the visible page, so
 * the column does not appear and vanish as somebody pages through.
 */
const showsBusiness = (rows: readonly SelectedUser[]) =>
    rows.some((user) => Boolean(user.businessLegalName));

/**
 * Whether one result answers the filter.
 *
 * <p>Every column the table shows, so what somebody types is matched against
 * what they are looking at. A search for a common surname comes back with
 * hundreds of people and the directory cannot narrow it further - the only way
 * to find the right Smith is to sift what came back.
 */
const matchesFilter = (user: SelectedUser, filter: string) => {
    const needle = filter.trim().toLowerCase();
    if (!needle) {
        return true;
    }
    return [
        user.userId,
        user.firstName,
        user.lastName,
        user.email,
        user.businessLegalName,
    ].some((field) => field?.toLowerCase().includes(needle));
};

/** The columns somebody can sort by, and how each reads off a row. */
const SORT_COLUMNS = {
    userId: { label: "Username", valueOf: (user: SelectedUser) => user.userId },
    firstName: {
        label: "First name",
        valueOf: (user: SelectedUser) => user.firstName,
    },
    lastName: {
        label: "Last name",
        valueOf: (user: SelectedUser) => user.lastName,
    },
    email: { label: "Email", valueOf: (user: SelectedUser) => user.email },
    businessLegalName: {
        label: "Business",
        valueOf: (user: SelectedUser) => user.businessLegalName,
    },
} as const;

type SortColumn = keyof typeof SORT_COLUMNS;
type SortDirection = "NONE" | "ASC" | "DESC";

/** Carbon's own cycle: unsorted, then up, then down, then unsorted again. */
const NEXT_DIRECTION: Record<SortDirection, SortDirection> = {
    NONE: "ASC",
    ASC: "DESC",
    DESC: "NONE",
};

/**
 * The results in the chosen order.
 *
 * <p>Sorted over every result rather than the visible page: a directory search
 * returns hundreds of people across forty pages, and a sort that only ordered
 * the ten on screen would answer a question nobody asked.
 *
 * <p>Empty values sort last in both directions. A missing surname is not the
 * first surname alphabetically, and burying the incomplete rows at the bottom is
 * what somebody scanning for a name actually wants.
 */
const sortRows = (
    rows: readonly SelectedUser[],
    column: SortColumn | null,
    direction: SortDirection
): readonly SelectedUser[] => {
    if (!column || direction === "NONE") {
        // The directory's own order, which is what the modal opens with.
        return rows;
    }
    const read = SORT_COLUMNS[column].valueOf;
    const sign = direction === "ASC" ? 1 : -1;

    return [...rows].sort((left, right) => {
        const a = read(left)?.trim() ?? "";
        const b = read(right)?.trim() ?? "";
        if (!a || !b) {
            return a === b ? 0 : a ? -1 : 1;
        }
        // Numeric, because a directory is full of usernames that are half
        // number, and those sort as text into a jumble. Case-insensitive for the
        // same reason the filter is: nobody is looking for the capitalised
        // Smiths.
        return (
            sign *
            a.localeCompare(b, undefined, {
                numeric: true,
                sensitivity: "base",
            })
        );
    });
};

export const UserSearchResultsModal: FC<Props> = ({
    open,
    rows,
    multiUserMode,
    onConfirm,
    onCancel,
}) => {
    const withBusiness = showsBusiness(rows);
    const [selectedKeys, setSelectedKeys] = useState<string[]>([]);
    const [filter, setFilter] = useState("");
    const [page, setPage] = useState(1);
    const [pageSize, setPageSize] = useState(PAGE_SIZES[0]);
    const [sortColumn, setSortColumn] = useState<SortColumn | null>(null);
    const [sortDirection, setSortDirection] = useState<SortDirection>("NONE");

    // A fresh search is a fresh choice: without this, ticking somebody, closing,
    // and searching again would open with the previous person still selected,
    // and with the previous search's filter still narrowing the new results.
    useEffect(() => {
        if (!open) {
            return;
        }
        setPage(1);
        setFilter("");
        setSortColumn(null);
        setSortDirection("NONE");
        // One result is not a choice, so it arrives already ticked.
        setSelectedKeys(rows.length === 1 ? [keyOf(rows[0])] : []);
    }, [open, rows]);

    const filteredRows = useMemo(
        () => rows.filter((user) => matchesFilter(user, filter)),
        [rows, filter]
    );

    const sortedRows = useMemo(
        () => sortRows(filteredRows, sortColumn, sortDirection),
        [filteredRows, sortColumn, sortDirection]
    );

    const pagedRows = useMemo(
        () => sortedRows.slice((page - 1) * pageSize, page * pageSize),
        [sortedRows, page, pageSize]
    );

    /** Cycles one column through Carbon's three states. */
    const sortBy = (column: SortColumn) => {
        const next =
            column === sortColumn ? NEXT_DIRECTION[sortDirection] : "ASC";
        setSortColumn(next === "NONE" ? null : column);
        setSortDirection(next);
        // The first page of the new order, not the twelfth page of the old one.
        setPage(1);
    };

    const toggle = (user: SelectedUser, checked: boolean) => {
        const key = keyOf(user);
        if (!multiUserMode) {
            setSelectedKeys(checked ? [key] : []);
            return;
        }
        setSelectedKeys((current) =>
            checked
                ? [...current, key]
                : current.filter((existing) => existing !== key)
        );
    };

    /*
        Selection survives the filter. Somebody ticking three people across two
        different filters has chosen three people, and narrowing the list is not
        a way of changing their mind - so this confirms from every row, not only
        the ones currently on screen.
    */
    const confirm = () =>
        onConfirm(rows.filter((user) => selectedKeys.includes(keyOf(user))));

    const columnCount = withBusiness ? 6 : 5;

    return (
        <ComposedModal
            open={open}
            size="lg"
            aria-label="User search results"
            onClose={() => {
                onCancel();
            }}
            className="user-search-results-modal"
            // The filter, so a long result set can be narrowed straight away.
            // Carbon would otherwise focus the first row's radio or checkbox,
            // which is a choice rather than a starting point.
            selectorPrimaryFocus=".user-search-results__filter input"
        >
            <ModalHeader title="User search results" />
            {/*
                ComposedModal rather than Modal: a passive Modal cannot be given
                a header and a body of its own, and everything - table,
                pagination and buttons - ended up in one scrolling block, so a
                hundred results pushed Cancel and Confirm out of sight. The
                buttons are the last thing in this body and stick to its bottom
                edge; see user-search-results__actions.
            */}
            <ModalBody hasScrollingContent aria-label="User search results">
                <div className="user-search-results">
                    <div className="user-search-results__toolbar">
                        <Search
                            className="user-search-results__filter"
                            id="user-search-results-filter"
                            labelText="Filter results"
                            placeholder="Filter these results"
                            size="md"
                            value={filter}
                            onChange={(event) => {
                                setFilter(event.target.value);
                                // Page 3 of the old list is rarely page 3 of the
                                // new one, and is often past its end.
                                setPage(1);
                            }}
                            onClear={() => {
                                setFilter("");
                                setPage(1);
                            }}
                        />
                        <p
                            className="user-search-results__count"
                            aria-live="polite"
                        >
                            {filter.trim()
                                ? `${filteredRows.length} of ${rows.length} results`
                                : `${rows.length} result${rows.length === 1 ? "" : "s"}`}
                        </p>
                    </div>

                    <TableContainer>
                        <Table size="md" useZebraStyles>
                            <TableHead>
                                <TableRow>
                                    <TableHeader aria-label="Select" />
                                    {(
                                        [
                                            "userId",
                                            "firstName",
                                            "lastName",
                                            "email",
                                            ...(withBusiness
                                                ? (["businessLegalName"] as const)
                                                : []),
                                        ] as SortColumn[]
                                    ).map((column) => (
                                        <TableHeader
                                            key={column}
                                            isSortable
                                            isSortHeader={sortColumn === column}
                                            sortDirection={
                                                sortColumn === column
                                                    ? sortDirection
                                                    : "NONE"
                                            }
                                            onClick={() => sortBy(column)}
                                        >
                                            {SORT_COLUMNS[column].label}
                                        </TableHeader>
                                    ))}
                                </TableRow>
                            </TableHead>
                            <TableBody>
                                {pagedRows.length === 0 ? (
                                    <TableRow>
                                        <TableCell colSpan={columnCount}>
                                            <span className="user-search-results__empty">
                                                No results match this filter.
                                            </span>
                                        </TableCell>
                                    </TableRow>
                                ) : null}
                                {pagedRows.map((user) => {
                                    const key = keyOf(user);
                                    const checked = selectedKeys.includes(key);
                                    return (
                                        <TableRow key={key}>
                                            <TableCell>
                                                {multiUserMode ? (
                                                    <Checkbox
                                                        id={`select-${key}`}
                                                        labelText={`Select ${user.userId}`}
                                                        hideLabel
                                                        checked={checked}
                                                        onChange={(_event, { checked: next }) =>
                                                            toggle(user, next)
                                                        }
                                                    />
                                                ) : (
                                                    <RadioButton
                                                        id={`select-${key}`}
                                                        name="user-search-result"
                                                        labelText={`Select ${user.userId}`}
                                                        hideLabel
                                                        checked={checked}
                                                        onChange={() => toggle(user, true)}
                                                    />
                                                )}
                                            </TableCell>
                                            <TableCell>{user.userId}</TableCell>
                                            <TableCell>{user.firstName}</TableCell>
                                            <TableCell>{user.lastName}</TableCell>
                                            <TableCell>{user.email}</TableCell>
                                            {withBusiness ? (
                                                <TableCell>
                                                    {user.businessLegalName || (
                                                        <span className="not-applicable">
                                                            {PLACE_HOLDER}
                                                        </span>
                                                    )}
                                                </TableCell>
                                            ) : null}
                                        </TableRow>
                                    );
                                })}
                            </TableBody>
                        </Table>
                    </TableContainer>

                    {filteredRows.length > PAGE_SIZES[0] ? (
                        <Pagination
                            page={page}
                            pageSize={pageSize}
                            pageSizes={PAGE_SIZES}
                            totalItems={filteredRows.length}
                            onChange={({ page: next, pageSize: nextSize }) => {
                                setPage(next);
                                setPageSize(nextSize);
                            }}
                            size="md"
                        />
                    ) : null}

                    {/*
                        Sticky rather than a modal footer of its own.

                        Carbon's footer row is fixed to the floor of the dialog,
                        which leaves a band of empty dialog under a short result
                        set; sizing that row to its content instead let a long one
                        push the table straight out of the bottom of the modal.
                        Sticking the buttons to the bottom of the scrolling area
                        does both jobs: they follow the table when it is short,
                        and stay in view over it when it is not.
                    */}
                    <div className="user-search-results__actions">
                        <Button kind="tertiary" size="md" onClick={onCancel}>
                            Cancel
                        </Button>
                        <Button
                            name="confirm-search-results"
                            size="md"
                            disabled={selectedKeys.length === 0}
                            onClick={confirm}
                        >
                            Confirm
                        </Button>
                    </div>
                </div>
            </ModalBody>
        </ComposedModal>
    );
};

export default UserSearchResultsModal;
