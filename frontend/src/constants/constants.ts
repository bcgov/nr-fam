import { THREE_MINUTES } from "@/constants/TimeUnits";

export const PLACE_HOLDER = "—";

export const DEFAULT_SKELETON_BORDER_RADIUS = "1rem";

export const FOREST_CLIENT_INPUT_MAX_LENGTH = 8;

/**
 * Shortest term the organisation autocomplete will search on.
 *
 * Matches the backend's own minimum. A shorter term is not worth a request: a
 * name search on one or two characters returns most of the province, and a
 * number search on them matches nothing, since client numbers are matched whole.
 */
export const FOREST_CLIENT_SEARCH_MIN_LENGTH = 3;

export const FAM_APPLICATION_NAME = "FAM";

export const FAM_APPLICATION_ID = 1;

export const DEFAULT_ROW_PER_PAGE = 100;

export const TABLE_PAGINATOR_TEMPLATE =
    "RowsPerPageDropdown CurrentPageReport PrevPageLink NextPageLink";

export const TABLE_CURRENT_PAGE_REPORT_TEMPLATE =
    "{first} - {last} of {totalRecords} items";

export const TABLE_ROWS_PER_PAGE = [10, 15, 20, 50, 100];

export const MINIMUM_SEARCH_STR_LEN = 3;

/**
 * How long the browser waits for an integration's full assignment list.
 *
 * The backend reads CSS once per role, and scope-specific roles accumulate - an
 * integration with hundreds of organisation-scoped roles needs far longer than
 * the 10s axios default. Timing out does not stop that work: the backend
 * finishes and then fails writing to a closed connection ("Broken pipe"), and
 * React Query's retry starts the whole fan-out again. Must stay under the
 * Route's haproxy timeout (5m, frontend/openshift.deploy.yml).
 */
export const ASSIGNMENT_LIST_TIMEOUT_MS = THREE_MINUTES;
