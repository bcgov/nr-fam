import {
    Button,
    Checkbox,
    Pagination,
    RadioButton,
    Table,
    TableBody,
    TableCell,
    TableContainer,
    TableHead,
    TableHeader,
    TableRow,
} from "@carbon/react";
import { useEffect, useMemo, useState, type FC } from "react";
import { Modal } from "@/components/Modal";
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

export const UserSearchResultsModal: FC<Props> = ({
    open,
    rows,
    multiUserMode,
    onConfirm,
    onCancel,
}) => {
    const [selectedKeys, setSelectedKeys] = useState<string[]>([]);
    const [page, setPage] = useState(1);
    const [pageSize, setPageSize] = useState(PAGE_SIZES[0]);

    // A fresh search is a fresh choice: without this, ticking somebody, closing,
    // and searching again would open with the previous person still selected.
    useEffect(() => {
        if (!open) {
            return;
        }
        setPage(1);
        // One result is not a choice, so it arrives already ticked.
        setSelectedKeys(rows.length === 1 ? [keyOf(rows[0])] : []);
    }, [open, rows]);

    const pagedRows = useMemo(
        () => rows.slice((page - 1) * pageSize, page * pageSize),
        [rows, page, pageSize]
    );

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

    const confirm = () =>
        onConfirm(rows.filter((user) => selectedKeys.includes(keyOf(user))));

    return (
        <Modal
            open={open}
            modalHeading="User search results"
            passiveModal
            size="lg"
            aria-label="User search results"
            onRequestClose={onCancel}
            className="user-search-results-modal"
        >
            <div className="user-search-results">
                <TableContainer>
                    <Table size="md" useZebraStyles>
                        <TableHead>
                            <TableRow>
                                <TableHeader aria-label="Select" />
                                <TableHeader>Username</TableHeader>
                                <TableHeader>First name</TableHeader>
                                <TableHeader>Last name</TableHeader>
                                <TableHeader>Email</TableHeader>
                            </TableRow>
                        </TableHead>
                        <TableBody>
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
                                    </TableRow>
                                );
                            })}
                        </TableBody>
                    </Table>
                </TableContainer>

                {rows.length > PAGE_SIZES[0] ? (
                    <Pagination
                        page={page}
                        pageSize={pageSize}
                        pageSizes={PAGE_SIZES}
                        totalItems={rows.length}
                        onChange={({ page: next, pageSize: nextSize }) => {
                            setPage(next);
                            setPageSize(nextSize);
                        }}
                        size="md"
                    />
                ) : null}

                <div className="user-search-results__actions">
                    <Button kind="tertiary" onClick={onCancel}>
                        Cancel
                    </Button>
                    <Button
                        name="confirm-search-results"
                        disabled={selectedKeys.length === 0}
                        onClick={confirm}
                    >
                        Confirm
                    </Button>
                </div>
            </div>
        </Modal>
    );
};

export default UserSearchResultsModal;
