import { useQuery } from "@tanstack/react-query";
import type { FamDistrictDto } from "fam-api";
import { useMemo, type FC } from "react";
import { AppActlApiService } from "@/services/ApiServiceFactory";
import { FixedScopePicker } from "./FixedScopePicker";

/**
 * District picker for one role.
 *
 * The selection belongs to the role that owns it: two district-scoped roles
 * chosen together need not cover the same districts, so this holds nothing of
 * its own and reports every change to its parent.
 *
 * The list and the chosen table come from FixedScopePicker, which regions share
 * - the two differ only in which fields carry the code and the name.
 */
type Props = {
    selected: FamDistrictDto[];
    onChange: (districts: FamDistrictDto[]) => void;
    /**
     * The only districts this caller may grant for, or null when they are not
     * restricted.
     *
     * Null and empty differ. Null is a FAM or application administrator, who may
     * grant every district. Empty is a delegated administrator whose delegation
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

export const DistrictSelectTable: FC<Props> = ({
    selected,
    onChange,
    allowed = null,
    title = "Restrict access by districts",
    subtitle = "Select one or more districts for this access",
    errorMessage,
}) => {
    const districtsQuery = useQuery({
        queryKey: ["districts"],
        queryFn: () =>
            AppActlApiService.districtsApi.getDistricts().then((res) => res.data),
        refetchOnMount: true,
    });

    /**
     * Expired districts are kept out of the picker so they cannot be granted,
     * while remaining valid on permissions that already reference them.
     */
    const available = useMemo<FamDistrictDto[]>(() => {
        const active = (districtsQuery.data ?? []).filter(
            (district) => !district.expired
        );
        // Undefined and null both mean "not restricted". Offering the full list
        // to a delegated administrator put every district in front of somebody
        // whose grant would be refused for all but their own.
        if (allowed == null) {
            return active;
        }
        const permitted = new Set(allowed);
        return active.filter((district) =>
            permitted.has(district.org_unit_code)
        );
    }, [districtsQuery.data, allowed]);

    return (
        <FixedScopePicker
            options={available}
            selected={selected}
            onChange={onChange}
            codeOf={(district) => district.org_unit_code}
            nameOf={(district) => district.org_unit_name}
            title={title}
            subtitle={subtitle}
            noun="District"
            emptyMessage={
                allowed != null
                    ? "You have not been delegated any district for this role"
                    : "No district available"
            }
            errorMessage={errorMessage}
            loadError={
                districtsQuery.isError
                    ? "Failed to fetch available districts. Please try again."
                    : undefined
            }
        />
    );
};

export default DistrictSelectTable;
