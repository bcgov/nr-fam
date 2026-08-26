import { useQuery } from "@tanstack/react-query";
import type { FamRegionDto } from "fam-api";
import { useMemo, type FC } from "react";
import { AppActlApiService } from "@/services/ApiServiceFactory";
import { FixedScopePicker } from "./FixedScopePicker";

/**
 * Region picker for one role.
 *
 * The district picker's twin - regions are a separate scope dimension, and a
 * role may require either or both, so a role scoped both ways shows both
 * pickers and is granted against the pair.
 */
type Props = {
    selected: FamRegionDto[];
    onChange: (regions: FamRegionDto[]) => void;
    /**
     * The only regions this caller may grant for, or null when they are not
     * restricted.
     *
     * Null and empty differ. Null is a FAM or application administrator, who may
     * grant every region. Empty is a delegated administrator whose delegation
     * names none - they may grant nothing here, and an empty picker is the
     * honest answer rather than the full list.
     */
    allowed?: string[] | null;
    /**
     * Wording, because the same picker answers two different questions: what a
     * person is being given, or what they may hand out.
     */
    title?: string;
    subtitle?: string;
    /** Shown when the step has been submitted with nothing chosen. */
    errorMessage?: string;
};

export const RegionSelectTable: FC<Props> = ({
    selected,
    onChange,
    allowed = null,
    title = "Restrict access by regions",
    subtitle = "Select one or more regions for this access",
    errorMessage,
}) => {
    const regionsQuery = useQuery({
        queryKey: ["regions"],
        queryFn: () =>
            AppActlApiService.regionsApi.getRegions().then((res) => res.data),
        refetchOnMount: true,
    });

    /**
     * Expired regions are kept out of the picker so they cannot be granted,
     * while remaining valid on permissions that already reference them.
     */
    const available = useMemo<FamRegionDto[]>(() => {
        const active = (regionsQuery.data ?? []).filter(
            (region) => !region.expired
        );
        // Undefined and null both mean "not restricted". Offering the full list
        // to a delegated administrator put every region in front of somebody
        // whose grant would be refused for all but their own.
        if (allowed == null) {
            return active;
        }
        const permitted = new Set(allowed);
        return active.filter((region) =>
            permitted.has(region.region_code)
        );
    }, [regionsQuery.data, allowed]);

    return (
        <FixedScopePicker
            options={available}
            selected={selected}
            onChange={onChange}
            codeOf={(region) => region.region_code}
            nameOf={(region) => region.region_name}
            title={title}
            subtitle={subtitle}
            noun="Region"
            emptyMessage={
                allowed != null
                    ? "You have not been delegated any region for this role"
                    : "No region available"
            }
            errorMessage={errorMessage}
            loadError={
                regionsQuery.isError
                    ? "Failed to fetch available regions. Please try again."
                    : undefined
            }
        />
    );
};

export default RegionSelectTable;
