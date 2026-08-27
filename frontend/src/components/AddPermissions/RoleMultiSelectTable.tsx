import {
    Checkbox,
    Table,
    TableBody,
    TableCell,
    TableContainer,
    TableExpandedRow,
    TableExpandHeader,
    TableExpandRow,
    TableHead,
    TableHeader,
    TableRow,
} from "@carbon/react";
import type { FamDistrictDto, FamForestClientDto, FamRegionDto } from "fam-api";
import { useMemo, type FC } from "react";
import { Chip } from "@/components/Chip";
import { PLACE_HOLDER } from "@/constants/constants";
import type { RoleOption } from "@/pages/AddAppPermission/grantUtils";
import { roleOptionKey, sortedByRole } from "@/utils/RoleSort";
import { requiresScope, type RoleScopeSelection } from "@/utils/ScopeUtils";
import { RoleScopeFields } from "./RoleScopeFields";
import "./RoleMultiSelectTable.css";

/**
 * Choosing roles, with each scoped role opening in place to say what it applies
 * to.
 *
 * The pickers used to sit in a step of their own further down the form, one card
 * per chosen role. Two roles ticked meant two cards, and matching a card back to
 * its role meant reading the pill in its header and scrolling up to the list.
 * Expanding the row puts the answer where the question is.
 *
 * Only a scoped role that has been ticked expands. A role granted for the whole
 * application has nothing to choose, and one not yet chosen has nothing to
 * choose it for - a chevron opening on an empty panel reads as a fault.
 */
type Props = {
    roleOptions: RoleOption[];
    /** Every chosen role with its scope, which is what the rows expand into. */
    selections: RoleScopeSelection[];
    /** Toggles one role. The parent owns the list, including its order. */
    onToggle: (role: RoleOption) => void;
    environment: string;
    onDistrictsChange: (roleName: string, districts: FamDistrictDto[]) => void;
    onRegionsChange: (roleName: string, regions: FamRegionDto[]) => void;
    onForestClientsChange: (
        roleName: string,
        clients: FamForestClientDto[]
    ) => void;
    districtTitle?: string;
    districtSubtitle?: string;
    regionTitle?: string;
    regionSubtitle?: string;
    clientTitle?: string;
    clientSubtitle?: string;
    /** Per-role complaints, keyed by role name. */
    errors?: Record<
        string,
        { districts?: string; regions?: string; forestClients?: string }
    >;
};

/** What a role has to be narrowed by, in the words the picker uses. */
const scopeTags = (role: RoleOption): string[] => {
    const tags: string[] = [];
    if (role.role_type_district) {
        tags.push("Per district");
    }
    if (role.role_type_region) {
        tags.push("Per region");
    }
    if (role.role_type_client) {
        tags.push("Per organization");
    }
    return tags;
};

export const RoleMultiSelectTable: FC<Props> = ({
    roleOptions,
    selections,
    onToggle,
    environment,
    onDistrictsChange,
    onRegionsChange,
    onForestClientsChange,
    errors = {},
    ...wording
}) => {
    /*
        Sorted here rather than by the caller, so every screen offering roles
        offers them in the same order. It has to happen before the rows are
        indexed, because the striping counts roles - see below.
    */
    const orderedRoles = useMemo(
        () => sortedByRole(roleOptions, roleOptionKey),
        [roleOptions]
    );

    return (
        <div className="fam-table role-multi-select-table">
            <TableContainer>
                <Table size="md">
                    <TableHead>
                        <TableRow>
                            <TableExpandHeader aria-label="Expand row" />
                            <TableHeader aria-label="Select" className="pick-col" />
                            <TableHeader>Role</TableHeader>
                            <TableHeader>Description</TableHeader>
                            <TableHeader>Scope</TableHeader>
                        </TableRow>
                    </TableHead>
                    <TableBody>
                        {orderedRoles.length === 0 ? (
                            <TableRow>
                                <TableCell colSpan={5}>No role available</TableCell>
                            </TableRow>
                        ) : (
                            orderedRoles.flatMap((role, index) => {
                                /*
                                    Striped per role rather than per row, and by
                                    hand rather than by Carbon.

                                    Carbon's zebra rules for an expandable table
                                    count in fours - parent, child, parent, child
                                    - because it assumes every row has a panel.
                                    Here only scoped roles do, so from the first
                                    unscoped role on, the arithmetic is off by
                                    one: it painted one role's panel white under
                                    a grey row and the next role's grey under a
                                    white one.
                                */
                                const shaded = index % 2 === 1;
                                const rowClass = `role-row${
                                    shaded ? " role-row--shaded" : ""
                                }`;
                                const tags = scopeTags(role);
                                const label = role.display_name ?? role.name;
                                const selection = selections.find(
                                    (one) => one.role.name === role.name
                                );
                                const scoped = requiresScope(role);

                                const cells = (
                                    <>
                                        <TableCell className="pick-col">
                                            <Checkbox
                                                id={`role-${role.name}`}
                                                // The role's own name, so a test
                                                // or a screen reader identifies
                                                // the row by what it is rather
                                                // than by position.
                                                labelText={label}
                                                hideLabel
                                                checked={selection !== undefined}
                                                onChange={() => onToggle(role)}
                                            />
                                        </TableCell>
                                        {/* The short name, falling back to the code. */}
                                        <TableCell>{label}</TableCell>
                                        <TableCell>
                                            {role.description ?? PLACE_HOLDER}
                                        </TableCell>
                                        <TableCell>
                                            {tags.length === 0 ? (
                                                <span className="no-scope">
                                                    Whole application
                                                </span>
                                            ) : (
                                                <span className="scope-tags">
                                                    {tags.map((tag) => (
                                                        <Chip
                                                            key={tag}
                                                            color="green"
                                                            label={tag}
                                                        />
                                                    ))}
                                                </span>
                                            )}
                                        </TableCell>
                                    </>
                                );

                                if (!scoped || !selection) {
                                    return [
                                        <TableRow key={role.name} className={rowClass}>
                                            <TableCell className="no-expand-col" />
                                            {cells}
                                        </TableRow>,
                                    ];
                                }

                                return [
                                    <TableExpandRow
                                        key={role.name}
                                        className={rowClass}
                                        /*
                                            Always. The guard above has already
                                            returned a plain row for anything
                                            unticked or unscoped, so reaching
                                            here means the panel belongs open.

                                            It used to be closable, which let
                                            somebody shut the very fields the
                                            form refuses to submit without - the
                                            submit then did nothing, with nothing
                                            on screen saying why. The panel is
                                            not a disclosure, it is the rest of
                                            the question the checkbox asks, so
                                            the checkbox is the only control
                                            over it.
                                        */
                                        isExpanded
                                        // Carbon requires the handler and always
                                        // draws a chevron; there is nothing for
                                        // either to do. The chevron is hidden in
                                        // the CSS rather than left inert, so
                                        // there is no control on screen that
                                        // looks like it does something.
                                        onExpand={() => undefined}
                                        aria-label={`Scope for ${label}`}
                                    >
                                        {cells}
                                    </TableExpandRow>,
                                    <TableExpandedRow
                                        key={`${role.name}-scope`}
                                        colSpan={5}
                                        // The panel carries its role's stripe so
                                        // it reads as part of that row rather
                                        // than as something underneath it.
                                        className={`${rowClass} role-scope-row`}
                                    >
                                        {/*
                                            Mounted with the row. The row itself
                                            only exists while the role is ticked,
                                            so the pickers never fetch their
                                            lists for a role nobody has chosen.
                                        */}
                                        <RoleScopeFields
                                                selection={selection}
                                                environment={environment}
                                                onDistrictsChange={(districts) =>
                                                    onDistrictsChange(
                                                        role.name,
                                                        districts
                                                    )
                                                }
                                                onRegionsChange={(regions) =>
                                                    onRegionsChange(role.name, regions)
                                                }
                                                onForestClientsChange={(clients) =>
                                                    onForestClientsChange(
                                                        role.name,
                                                        clients
                                                    )
                                                }
                                                districtError={
                                                    errors[role.name]?.districts
                                                }
                                                regionError={
                                                    errors[role.name]?.regions
                                                }
                                                clientError={
                                                    errors[role.name]?.forestClients
                                                }
                                                {...wording}
                                            />
                                    </TableExpandedRow>,
                                ];
                            })
                        )}
                    </TableBody>
                </Table>
            </TableContainer>
        </div>
    );
};

export default RoleMultiSelectTable;
