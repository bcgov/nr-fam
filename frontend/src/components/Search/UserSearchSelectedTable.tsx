import { TrashCan } from "@carbon/icons-react";
import {
    Table,
    TableBody,
    TableCell,
    TableContainer,
    TableHead,
    TableHeader,
    TableRow,
} from "@carbon/react";
import type { FC } from "react";
import type { SelectedUser } from "@/types/SelectUserType";
import { formatUserNameAndId } from "@/utils/UserUtils";
import "./UserSearchSelectedTable.css";

/**
 * The users chosen so far, and the way to take one back off the list.
 *
 * UserSearch owns the selection; this only draws it. The bar underneath says how
 * many people the form below applies to, because in multi-user mode everything
 * chosen after this point is granted to all of them at once.
 */
type Props = {
    users: readonly SelectedUser[];
    multiUserMode?: boolean;
    onDelete: (userId: string) => void;
};

export const UserSearchSelectedTable: FC<Props> = ({
    users,
    multiUserMode,
    onDelete,
}) => {
    if (users.length === 0) {
        return null;
    }

    return (
        <div>
            {/*
                `fam-table` is where the border and surface come from - the same
                class the role and scope tables carry. Without it this table sat
                on the page with no edge at all while every table below it had
                one.
            */}
            <div className="user-id-card-table fam-table">
                <TableContainer>
                    <Table size="md" useZebraStyles className="user-table">
                        <TableHead>
                            <TableRow>
                                <TableHeader>Username</TableHeader>
                                <TableHeader>Full Name</TableHeader>
                                <TableHeader>Email</TableHeader>
                                <TableHeader aria-label="Actions" />
                            </TableRow>
                        </TableHead>
                        <TableBody>
                            {users.map((user) => (
                                <TableRow key={`${user.userId}-${user.sourceDomain}`}>
                                    <TableCell>{user.userId}</TableCell>
                                    <TableCell>
                                        {formatUserNameAndId(
                                            null,
                                            user.firstName,
                                            user.lastName
                                        )}
                                    </TableCell>
                                    <TableCell>{user.email}</TableCell>
                                    <TableCell className="action-col">
                                        <button
                                            className="btn btn-icon"
                                            type="button"
                                            title="Delete user"
                                            aria-label={`Remove ${user.userId}`}
                                            onClick={() => onDelete(user.userId)}
                                        >
                                            <TrashCan />
                                        </button>
                                    </TableCell>
                                </TableRow>
                            ))}
                        </TableBody>
                    </Table>
                </TableContainer>
            </div>

            {multiUserMode ? (
                <div className="user-bulk-message-bar">
                    <span>
                        <b>{`${users.length} user${users.length > 1 ? "s" : ""}`}</b>
                        &nbsp;will receive the same permissions configured below
                    </span>
                </div>
            ) : null}
        </div>
    );
};

export default UserSearchSelectedTable;
