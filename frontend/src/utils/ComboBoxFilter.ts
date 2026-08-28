/**
 * Filtering for Carbon's ComboBox.
 *
 * <p><b>Carbon does not filter one by default.</b> `shouldFilterItem` defaults to
 * `() => true`, so a ComboBox given no filter shows its whole list however much
 * is typed - Downshift still jumps the highlight to the nearest match, which
 * reads as a list that scrolls rather than one that narrows. Every ComboBox over
 * a list FAM already holds passes this.
 *
 * <p>Not for the organisation picker: its items are search results the backend
 * has already chosen for the term, and filtering them again here would hide the
 * ones it matched on a field the visible text does not contain - an acronym, for
 * instance.
 */
export type ComboBoxFilterArgs<T> = {
    item: T;
    /**
     * Optional, because Carbon's own type says so: a ComboBox may leave it out
     * and fall back to Carbon's default stringifier. The fallback here is the
     * same idea - the item's own `toString` - so an item is never silently
     * filtered out for want of a formatter.
     */
    itemToString?: (item: T) => string;
    inputValue: string | null;
};

/**
 * Whether one item survives what has been typed.
 *
 * <p>Case-insensitive containment rather than a prefix match: people search for
 * the distinctive part of a name, and "chilcotin" should find
 * "Cariboo-Chilcotin". Whitespace-only input filters nothing, so clearing the
 * box brings the whole list back rather than emptying it.
 */
export const matchesTypedText = <T>({
    item,
    itemToString,
    inputValue,
}: ComboBoxFilterArgs<T>): boolean => {
    const typed = inputValue?.trim().toLowerCase() ?? "";
    if (typed === "") {
        return true;
    }
    const text = itemToString ? itemToString(item) : String(item ?? "");
    return text.toLowerCase().includes(typed);
};

/**
 * The same filter, for a picker that keeps its selection.
 *
 * <p>Carbon leaves the chosen item's own text in the box, so a plain filter
 * narrows the list to the thing already chosen the moment it is reopened - the
 * picker then looks like it holds one application. Text that is still exactly
 * the selection is treated as nothing typed, which is what it is: the person has
 * opened the list, not searched it. The first keystroke that changes it filters
 * as usual.
 */
export const matchesTypedTextBeside =
    (selectedLabel: string | null | undefined) =>
    <T,>(args: ComboBoxFilterArgs<T>): boolean => {
        const typed = args.inputValue?.trim() ?? "";
        if (typed !== "" && typed === (selectedLabel ?? "").trim()) {
            return true;
        }
        return matchesTypedText(args);
    };
