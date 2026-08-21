import { PERMISSION_REQUIRED_FOR_OPERATION } from "@/constants/ApiErrorCodes";
import { AppActlApiService } from "@/services/ApiServiceFactory";
import type { SelectedUser } from "@/types/SelectUserType";
import type { UserSearchParams } from "@/types/UserSearchTypes";
import { useMutation } from "@tanstack/vue-query";
import { isAxiosError } from "axios";
import type {
    UserLookupBceidUserDto,
    UserLookupIdirUserDto,
} from "fam-api";
import { UserType } from "fam-api/model";
import { ref } from "vue";

/**
 * Composable/service that provides user search functionality using nr-user-lookup-api.
 * This encapsulates the API call logic, normalization of results, and error handling for user searches
 * with tanstack/vue-query useMutation.
 */

const IDIR_SEARCH_PAGE_SIZE = 500;

export type UserSearchError = {
    message: string;
    code?: string;
    description?: string;
};

function normalizeIdirItem(
    item: UserLookupIdirUserDto
): SelectedUser {
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
}

function normalizeBceidItem(item: UserLookupBceidUserDto): SelectedUser {
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
}

export function useUserSearchApiService() {
    const searchError = ref<UserSearchError | null>(null);

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
                    // A broad directory search under load can be slow, so this
                    // overrides the 10 second default set in ApiServiceFactory
                    // for this call only.
                    { timeout: 20000 }
                );
                return res.data.items.map(normalizeIdirItem);
            } else {
                const res = await AppActlApiService.idirBceidProxyApi.bceidLookup(
                    params.searchText
                );
                if (!res.data.found) {
                    return [];
                }
                return [normalizeBceidItem(res.data)];
            }
        },
        onError: (error: unknown) => {
            console.error("User search failed:", error);
            if (isAxiosError(error) && error.response?.status === 403) {
                const detail = error.response.data?.detail;
                if (detail?.code === PERMISSION_REQUIRED_FOR_OPERATION) {
                    searchError.value = {
                        message: detail.description,
                        code: detail.code,
                        description: detail.description,
                    };
                    return;
                }
            }

            searchError.value = {
                message:
                    "Search failed. Please try again or contact support if the issue persists.",
            };
        },
        onSuccess: () => {
            searchError.value = null;
        },
    });

    const searchUsers = (params: UserSearchParams) => {
        searchMutation.reset();
        searchError.value = null;
        searchMutation.mutate(params);
    };

    return {
        searchUsers,
        isPending: searchMutation.isPending,
        searchResults: searchMutation.data,
        isSuccess: searchMutation.isSuccess,
        searchError,
        reset: () => {
            searchMutation.reset();
            searchError.value = null;
        },
    };
}
