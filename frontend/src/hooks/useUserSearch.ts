import { useMutation } from "@tanstack/react-query";
import { isAxiosError } from "axios";
import type { UserLookupBceidUserDto, UserLookupIdirUserDto } from "fam-api";
import { UserType } from "fam-api/model";
import { useCallback, useState } from "react";
import { PERMISSION_REQUIRED_FOR_OPERATION } from "@/constants/ApiErrorCodes";
import { AppActlApiService } from "@/services/ApiServiceFactory";
import type { SelectedUser } from "@/types/SelectUserType";
import type { UserSearchParams } from "@/types/UserSearchTypes";

/**
 * Searching the IDIR and BCeID directories through nr-user-lookup-api.
 *
 * The two directories answer nothing alike - IDIR returns a page of matches,
 * BCeID answers about exactly one username and says whether it found it - so
 * both are normalised to SelectedUser here rather than at every call site.
 */

const IDIR_SEARCH_PAGE_SIZE = 500;

/**
 * Longer than the 10 second default set in ApiServiceFactory, for this call
 * only: a broad directory search under load is genuinely slow, and a timeout
 * reads to the user as "no such person".
 */
const IDIR_SEARCH_TIMEOUT_MS = 20_000;

export type UserSearchError = {
    message: string;
    code?: string;
    description?: string;
};

const normalizeIdir = (item: UserLookupIdirUserDto): SelectedUser => {
    const firstName = item.firstName ?? "";
    const lastName = item.lastName ?? "";
    return {
        userId: item.userId ?? "",
        guid: item.guid ?? null,
        firstName,
        lastName,
        email: item.email ?? "",
        fullName: [firstName, lastName].filter(Boolean).join(" "),
        sourceDomain: UserType.Idir,
    };
};

const normalizeBceid = (item: UserLookupBceidUserDto): SelectedUser => {
    const firstName = item.firstName ?? "";
    const lastName = item.lastName ?? "";
    return {
        userId: item.userId ?? "",
        guid: item.guid ?? null,
        firstName,
        lastName,
        email: item.email ?? "",
        fullName: [firstName, lastName].filter(Boolean).join(" "),
        sourceDomain: UserType.BceidBus,
    };
};

export const useUserSearch = () => {
    const [searchError, setSearchError] = useState<UserSearchError | null>(null);

    const searchMutation = useMutation({
        retry: 1,
        mutationFn: async (params: UserSearchParams): Promise<SelectedUser[]> => {
            if (params.domain === UserType.Idir) {
                const firstName =
                    params.searchType === "firstName" ? params.searchText : undefined;
                const lastName =
                    params.searchType === "lastName" ? params.searchText : undefined;
                const userId =
                    params.searchType === "username" ? params.searchText : undefined;

                const res = await AppActlApiService.idirBceidProxyApi.searchIdirUsers(
                    firstName,
                    lastName,
                    userId,
                    IDIR_SEARCH_PAGE_SIZE,
                    { timeout: IDIR_SEARCH_TIMEOUT_MS }
                );
                return res.data.items.map(normalizeIdir);
            }

            const res = await AppActlApiService.idirBceidProxyApi.bceidLookup(
                params.searchText
            );
            // Not an error: the username simply is not in the directory, and the
            // caller reports "no results" the same way it does for IDIR.
            return res.data.found ? [normalizeBceid(res.data)] : [];
        },
        onError: (error: unknown) => {
            console.error("User search failed:", error);
            if (isAxiosError(error) && error.response?.status === 403) {
                const detail = error.response.data?.detail;
                if (detail?.code === PERMISSION_REQUIRED_FOR_OPERATION) {
                    // The backend explains which permission is missing, which is
                    // what tells a BCeID administrator they are reaching outside
                    // their own organisation.
                    setSearchError({
                        message: detail.description,
                        code: detail.code,
                        description: detail.description,
                    });
                    return;
                }
            }

            setSearchError({
                message:
                    "Search failed. Please try again or contact support if the issue persists.",
            });
        },
        onSuccess: () => setSearchError(null),
    });

    const { mutate, reset: resetMutation } = searchMutation;

    const searchUsers = useCallback(
        (params: UserSearchParams) => {
            // Reset first: without it a repeat of the same search leaves
            // isSuccess already true, and the caller that opens the results
            // dialog on that edge never sees one.
            resetMutation();
            setSearchError(null);
            mutate(params);
        },
        [mutate, resetMutation]
    );

    const reset = useCallback(() => {
        resetMutation();
        setSearchError(null);
    }, [resetMutation]);

    return {
        searchUsers,
        isPending: searchMutation.isPending,
        searchResults: searchMutation.data,
        isSuccess: searchMutation.isSuccess,
        searchError,
        reset,
    };
};
