import type { FamDistrictDto, FamForestClientDto, FamRegionDto } from "fam-api";
import type { FC } from "react";
import {
    MAX_SCOPE_COMBINATIONS,
    scopeCombinationCount,
    type RoleScopeSelection,
} from "@/utils/ScopeUtils";
import { DistrictSelectTable } from "./DistrictSelectTable";
import { ForestClientAddTable } from "./ForestClientAddTable";
import { ForestClientSelectTable } from "./ForestClientSelectTable";
import { RegionSelectTable } from "./RegionSelectTable";
import "./RoleScopeFields.css";

/**
 * What one chosen role is scoped to, shown inside that role's own row.
 *
 * This used to be a card in a step of its own further down the form. Two roles
 * ticked meant two cards, and matching a card to its role meant reading the pill
 * in its header and scrolling back up to the list. Inline, the answer to "what
 * does this role apply to" is in the row that asks it.
 *
 * The count is the part worth keeping visible. A role scoped by district and
 * organisation applies per <em>pair</em>, so three districts and two
 * organisations is six - which is not obvious from the selections, and is what
 * runs into the backend's ceiling.
 */
type Props = {
    selection: RoleScopeSelection;
    environment: string;
    onDistrictsChange: (districts: FamDistrictDto[]) => void;
    onRegionsChange: (regions: FamRegionDto[]) => void;
    onForestClientsChange: (clients: FamForestClientDto[]) => void;
    districtTitle?: string;
    districtSubtitle?: string;
    regionTitle?: string;
    regionSubtitle?: string;
    clientTitle?: string;
    clientSubtitle?: string;
    districtError?: string;
    regionError?: string;
    clientError?: string;
};

export const RoleScopeFields: FC<Props> = ({
    selection,
    environment,
    onDistrictsChange,
    onRegionsChange,
    onForestClientsChange,
    districtTitle = "Districts",
    districtSubtitle = "Select one or more districts for this role",
    regionTitle = "Regions",
    regionSubtitle = "Select one or more regions for this role",
    clientTitle = "Organizations",
    clientSubtitle = "Add one or more organizations for this role",
    districtError,
    regionError,
    clientError,
}) => {
    /**
     * The organisations this caller is confined to, or null when they are not.
     *
     * Undefined and null both mean unrestricted; an empty array does not, and is
     * why this is not a truthiness check. A delegated administrator whose
     * delegation names no organisation gets the list, empty, saying so - rather
     * than a search box offering every organisation in the province.
     */
    const restrictedClients = selection.role.grantable_forest_clients ?? null;

    /*
        Still counted, no longer announced. The running total sat above the
        pickers restating what the tables below already showed, and cost a line
        of text plus its margins at the top of every expanded row. What it was
        genuinely for is the ceiling - which now speaks only when it is about to
        be crossed.
    */
    const overTheLimit =
        scopeCombinationCount(selection) > MAX_SCOPE_COMBINATIONS;

    return (
        <div className="role-scope-fields">
            {overTheLimit ? (
                // Said before the request rather than after: the backend refuses
                // anything past this, and finding out on submit means re-doing
                // the whole selection.
                <p className="role-scope-fields__limit">
                    {`That is more than the ${MAX_SCOPE_COMBINATIONS} one role can carry. Narrow the selection below.`}
                </p>
            ) : null}

            {selection.role.role_type_district ? (
                <DistrictSelectTable
                    selected={selection.districts}
                    onChange={onDistrictsChange}
                    allowed={selection.role.grantable_districts ?? null}
                    title={districtTitle}
                    subtitle={districtSubtitle}
                    errorMessage={districtError}
                />
            ) : null}

            {selection.role.role_type_region ? (
                <RegionSelectTable
                    selected={selection.regions}
                    onChange={onRegionsChange}
                    allowed={selection.role.grantable_regions ?? null}
                    title={regionTitle}
                    subtitle={regionSubtitle}
                    errorMessage={regionError}
                />
            ) : null}

            {/*
                Independent of the other pickers, not an else: a role may require
                a district AND a forest client, and it applies to each pair.
                Chained, the second picker would never render while validation
                still demanded a value for it - a form that cannot be submitted
                and does not say why.

                Two shapes, chosen by whether the caller is restricted. A
                delegation names a handful of organisations, so those are listed;
                anyone who may grant any of them searches instead.
            */}
            {selection.role.role_type_client ? (
                restrictedClients ? (
                    <ForestClientSelectTable
                        selected={selection.forestClients}
                        options={restrictedClients}
                        onChange={onForestClientsChange}
                        title={clientTitle}
                        subtitle={clientSubtitle}
                        errorMessage={clientError}
                    />
                ) : (
                    <ForestClientAddTable
                        environment={environment}
                        selected={selection.forestClients}
                        onChange={onForestClientsChange}
                        title={clientTitle}
                        subtitle={clientSubtitle}
                        errorMessage={clientError}
                    />
                )
            ) : null}
        </div>
    );
};

export default RoleScopeFields;
