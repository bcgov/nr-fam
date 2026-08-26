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
import { useEffect, useState, type FC } from "react";
import { Chip } from "@/components/Chip";
import { PLACE_HOLDER } from "@/constants/constants";
import type { RoleOption } from "@/pages/AddAppPermission/grantUtils";
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
    countNoun?: string;
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
    const [expanded, setExpanded] = useState<string[]>([]);

    /*
        A role that needs narrowing opens as soon as it is ticked, and drops out
        of the open set when it is unticked. Left shut, it would hide the very
        fields the form refuses to submit without, with nothing on screen saying
        why - which is the failure the old separate step at least made obvious by
        appearing.
    */
    useEffect(() => {
        const needing = selections
            .filter((one) => requiresScope(one.role))
            .map((one) => one.role.name);
        setExpanded((open) => [
            ...open.filter((name) => needing.includes(name)),
            ...needing.filter((name) => !open.includes(name)),
        ]);
    }, [selections]);

    const toggleExpanded = (name: string) =>
        setExpanded((open) =>
            open.includes(name)
                ? open.filter((one) => one !== name)
                : [...open, name]
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
                        {roleOptions.length === 0 ? (
                            <TableRow>
                                <TableCell colSpan={5}>No role available</TableCell>
                            </TableRow>
                        ) : (
                            roleOptions.flatMap((role, index) => {
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
                                const isOpen =
                                    Boolean(selection) &&
                                    scoped &&
                                    expanded.includes(role.name);

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
                                        isExpanded={isOpen}
                                        onExpand={() => toggleExpanded(role.name)}
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
                                            Mounted only while open: the pickers
                                            fetch their lists, and a closed row
                                            should not be asking CSS for
                                            organisations nobody is looking at.
                                        */}
                                        {isOpen ? (
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
                                        ) : null}
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
