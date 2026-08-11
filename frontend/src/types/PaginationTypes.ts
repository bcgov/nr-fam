/**
 * Client-side pagination state.
 *
 * `sortOrder` and `sortBy` were enums on the paged user-role listing, which went
 * to CSS - the CSS assignment listing is unpaged and unsorted server-side, so
 * sorting is the table's own concern now.
 */
export type PaginationType = {
    pageNumber: number;
    pageSize: number;
    search: string | null;
};
