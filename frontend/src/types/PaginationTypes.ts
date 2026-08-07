import type {
    SortOrder,
    UserRoleSortBy,
} from "fam-api/model";

export type PaginationType = {
    pageNumber: number;
    pageSize: number;
    search: string | null;
    sortOrder: SortOrder | null;
    sortBy: UserRoleSortBy | null;
};
