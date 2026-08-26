import {
    Checkbox,
    Table,
    TableBody,
    TableCell,
    TableContainer,
    TableHead,
    TableHeader,
    TableRow,
} from "@carbon/react";
import type { FamForestClientDto } from "fam-api";
import type { FC } from "react";
import { Chip } from "@/components/Chip";
import { PLACE_HOLDER } from "@/constants/constants";
import { SubsectionTitle } from "@/components/SubsectionTitle";
import "./ScopeSelectTable.css";

/**
 * Organisations as a checkbox list, for a caller who may only grant a few.
 *
 * The search box this replaces is the right shape when the answer could be any
 * of tens of thousands of organisations. A delegated administrator's delegation
 * names a handful, and searching for something you have already been told the
 * whole of is work for no reason - you would type, wait, and find the same five
 * rows every time.
 *
 * Deliberately the same shape as the district picker, which has always been a
 * list for the same reason: a fixed, short, known set.
 *
 * <b>Only rendered when restricted.</b> An application administrator may grant
 * any organisation, so their picker stays a search - see ForestClientAddTable.
 */
type Props = {
    selected: FamForestClientDto[];
    /** Exactly the organisations this caller may grant for. */
    options: FamForestClientDto[];
    onChange: (clients: FamForestClientDto[]) => void;
    title?: string;
    subtitle?: string;
    errorMessage?: string;
};

export const ForestClientSelectTable: FC<Props> = ({
    selected,
    options,
    onChange,
    title = "Organizations",
    subtitle = "Select one or more organizations for this role",
    errorMessage,
}) => {
    const toggle = (client: FamForestClientDto) => {
        const index = selected.findIndex(
            (chosen) =>
                chosen.forest_client_number === client.forest_client_number
        );
        onChange(
            index >= 0
                ? selected.filter((_, position) => position !== index)
                : [...selected, client]
        );
    };

    return (
        <div className="scope-select-table-container">
            <SubsectionTitle title={title} subtitle={subtitle} />

            {/*
                A list of checkboxes has no field to mark invalid, so the
                complaint takes Carbon's form-requirement styling by hand -
                the same red line a text field would show, and in the same
                place, without being a notification box.
            */}
            {errorMessage ? (
                <div className="cds--form-requirement" role="alert">
                    {errorMessage}
                </div>
            ) : null}

            <div className="fam-table">
                <TableContainer>
                    <Table size="md" useZebraStyles>
                        <TableHead>
                            <TableRow>
                                <TableHeader aria-label="Select" />
                                <TableHeader>Client number</TableHeader>
                                <TableHeader>Name</TableHeader>
                                <TableHeader>Status</TableHeader>
                            </TableRow>
                        </TableHead>
                        <TableBody>
                            {options.length === 0 ? (
                                <TableRow>
                                    {/*
                                        Said plainly rather than left blank. An
                                        empty table with no explanation reads as
                                        a screen that failed to load.
                                    */}
                                    <TableCell colSpan={4}>
                                        You have not been delegated any
                                        organization for this role
                                    </TableCell>
                                </TableRow>
                            ) : (
                                options.map((client) => (
                                    <TableRow key={client.forest_client_number}>
                                        <TableCell>
                                            <Checkbox
                                                id={`client-${client.forest_client_number}`}
                                                labelText={
                                                    client.client_name ??
                                                    client.forest_client_number
                                                }
                                                hideLabel
                                                checked={selected.some(
                                                    (chosen) =>
                                                        chosen.forest_client_number ===
                                                        client.forest_client_number
                                                )}
                                                onChange={() => toggle(client)}
                                            />
                                        </TableCell>
                                        <TableCell>
                                            {client.forest_client_number}
                                        </TableCell>
                                        <TableCell>
                                            {client.client_name ?? PLACE_HOLDER}
                                        </TableCell>
                                        <TableCell>
                                            {client.status ? (
                                                <Chip
                                                    color="green"
                                                    // Both are optional on
                                                    // the DTO; the code is a
                                                    // poor label but a better
                                                    // one than an empty pill.
                                                    label={
                                                        client.status
                                                            .description ??
                                                        client.status
                                                            .status_code ??
                                                        ""
                                                    }
                                                />
                                            ) : null}
                                        </TableCell>
                                    </TableRow>
                                ))
                            )}
                        </TableBody>
                    </Table>
                </TableContainer>
            </div>
        </div>
    );
};

export default ForestClientSelectTable;
