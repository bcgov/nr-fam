/**
 * One ordering for every table that shows or offers roles.
 *
 * Roles arrived in whatever order CSS returned them, which is neither
 * alphabetical nor stable - so the same application listed its roles differently
 * between two visits, and finding one meant reading the whole column.
 *
 * The key is the role's code where it has one, and its name where it does not.
 * Both are machine identifiers in the same uppercase style, so they order
 * together sensibly; the display name would not, being prose written by whoever
 * defined the role.
 */

/**
 * Compares two role identifiers.
 *
 * `numeric` so a role ending in 2 comes before one ending in 10, which plain
 * string ordering gets backwards. `sensitivity: "base"` so a lower-case code -
 * CSS does not enforce a case - does not sort into a group of its own after
 * every upper-case one.
 */
export const compareRoleKeys = (a: string, b: string): number =>
    a.localeCompare(b, undefined, { numeric: true, sensitivity: "base" });

/**
 * A comparator over whatever identifier the caller pulls off its own row shape.
 *
 * The shapes genuinely differ - a grantable role option, an assignment row, a
 * delegation - and only some of them carry a code at all, so the choice of key
 * belongs at the call site while the ordering stays here.
 */
export const byRoleKey =
    <T>(keyOf: (item: T) => string | undefined | null) =>
    (a: T, b: T): number =>
        compareRoleKeys(keyOf(a) ?? "", keyOf(b) ?? "");

/** The key for a role that may carry a machine code beneath its display role. */
export const roleOptionKey = (role: {
    role_code?: string | null;
    name: string;
}): string => role.role_code ?? role.name;

/**
 * Sorted, without disturbing the caller's array.
 *
 * `toSorted` would be tidier but is too new for the browsers this has to run in,
 * and sorting in place would mutate a react-query cache entry - which is shared,
 * and which nothing else expects to be reordered underneath it.
 */
export const sortedByRole = <T>(
    items: readonly T[],
    keyOf: (item: T) => string | undefined | null
): T[] => [...items].sort(byRoleKey(keyOf));
