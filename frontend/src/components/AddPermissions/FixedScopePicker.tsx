import {
    ComboBox,
    Table,
    TableBody,
    TableCell,
    TableContainer,
    TableHead,
    TableHeader,
    TableRow,
} from "@carbon/react";
import { useState } from "react";
import { SubsectionTitle } from "@/components/SubsectionTitle";
import { useErrorToast } from "@/context/notification/useErrorToast";
import "./ScopeSelectTable.css";
import { RemoveButton } from "@/components/RemoveButton";
import { matchesTypedText } from "@/utils/ComboBoxFilter";

/**
 * Pick values from a fixed list, one at a time, into a table of what is chosen.
 *
 * The same shape as the organisation picker: a select box above, the running
 * selection below, each row removable. Districts and regions used to be long
 * checkbox lists, which put forty rows on screen for a choice that is usually
 * one or two - and made the three scope pickers on a compound role read as three
 * unrelated widgets.
 *
 * A ComboBox rather than a plain Select because the lists are long enough to
 * want typing: forty-odd districts is more than anybody scrolls happily.
 *
 * Districts and regions differ only in which fields carry the code and the name,
 * so both arrive through accessors rather than each having its own copy of this.
 */
type Props<T> = {
    /** Everything the caller may pick, already filtered to what is grantable. */
    options: readonly T[];
    selected: readonly T[];
    onChange: (next: T[]) => void;
    /** The stable identity of an option - a district code, a region code. */
    codeOf: (item: T) => string;
    /** What a person reads: the district's or region's name. */
    nameOf: (item: T) => string;
    title: string;
    subtitle: string;
    /** Singular, for the field label and the table heading, e.g. "District". */
    noun: string;
    /** Shown when the list is empty, which is not the same as nothing chosen. */
    emptyMessage: string;
    /** Set once the form has been submitted with nothing chosen. */
    errorMessage?: string;
    /** A failed load, so the picker says why rather than looking empty. */
    loadError?: string;
};

export const FixedScopePicker = <T,>({
    options,
    selected,
    onChange,
    codeOf,
    nameOf,
    title,
    subtitle,
    noun,
    emptyMessage,
    errorMessage,
    loadError,
}: Props<T>) => {
    /*
        Carbon's ComboBox owns its input text, so choosing an item is undone by
        remounting rather than by clearing a controlled value - otherwise the
        chosen name stays sitting in what is a picker, reading as a value that
        was kept rather than one that was moved into the table below.
    */
    const [clearCount, setClearCount] = useState(0);

    /*
        A failed list is a failure of the screen, so it says so from the corner
        rather than as a box wedged between this picker's heading and its field.
        The field says so too, below - the toast is dismissable and the reason
        the picker is empty should outlive it.
    */
    useErrorToast({
        when: Boolean(loadError),
        title: loadError ?? "",
        occurrence: noun,
    });

    const chosen = new Set(selected.map(codeOf));
    const offered = options.filter((item) => !chosen.has(codeOf(item)));

    const add = (item: T | null | undefined) => {
        if (!item || chosen.has(codeOf(item))) {
            return;
        }
        onChange([...selected, item]);
        setClearCount((count) => count + 1);
    };

    const remove = (code: string) =>
        onChange(selected.filter((item) => codeOf(item) !== code));

    return (
        <div className="scope-select-table-container fixed-scope-picker">
            <SubsectionTitle title={title} subtitle={subtitle} />

            <ComboBox
                key={clearCount}
                id={`${noun.toLowerCase()}-picker`}
                className="fixed-scope-picker__input"
                titleText={noun}
                placeholder={
                    options.length === 0 ? emptyMessage : `Choose a ${noun.toLowerCase()}`
                }
                items={offered}
                itemToString={(item: T | null) => (item ? nameOf(item) : "")}
                // Carbon shows the whole list otherwise - see matchesTypedText.
                shouldFilterItem={matchesTypedText}
                selectedItem={null}
                disabled={options.length === 0}
                onChange={({ selectedItem }) => add(selectedItem)}
                /*
                    Both complaints belong to the field rather than to a box
                    above it: "select a district" points at nothing useful from
                    the corner of the screen, and this row may be collapsed or
                    scrolled away when the form is submitted. The load failure
                    is here as well as in its toast so the reason the list is
                    empty survives the toast being dismissed.
                */
                invalid={Boolean(loadError || errorMessage)}
                invalidText={loadError ?? errorMessage}
                helperText={
                    // Already-chosen values are dropped from the list rather than
                    // offered and then ignored, so the count says what is left.
                    options.length === 0
                        ? emptyMessage
                        : `${offered.length} of ${options.length} still to choose from`
                }
            />

            <div className="fam-table">
                <TableContainer>
                    <Table size="md" useZebraStyles>
                        <TableHead>
                            <TableRow>
                                <TableHeader>{noun}</TableHeader>
                                <TableHeader>Code</TableHeader>
                                <TableHeader>Action</TableHeader>
                            </TableRow>
                        </TableHead>
                        <TableBody>
                            {selected.length === 0 ? (
                                <TableRow>
                                    <TableCell colSpan={3}>
                                        {`No ${noun.toLowerCase()} added yet`}
                                    </TableCell>
                                </TableRow>
                            ) : (
                                selected.map((item) => (
                                    <TableRow key={codeOf(item)}>
                                        <TableCell>{nameOf(item)}</TableCell>
                                        <TableCell>{codeOf(item)}</TableCell>
                                        <TableCell>
                                            <RemoveButton
                                                accessible={`Remove ${nameOf(item)}`}
                                                onClick={() => remove(codeOf(item))}
                                            />
                                        </TableCell>
                                    </TableRow>
                                ))
                            )}
                        </TableBody>
                    </Table>
                </TableContainer>
            </div>
        </div>
    );
};

export default FixedScopePicker;
