import { describe, expect, it } from "vitest";
import { matchesTypedText, matchesTypedTextBeside } from "./ComboBoxFilter";

/**
 * Filtering for Carbon's ComboBox.
 *
 * Carbon's `shouldFilterItem` defaults to `() => true`, so a ComboBox given no
 * filter shows its whole list however much is typed - Downshift moves the
 * highlight to the nearest match, which reads as a list that scrolls rather than
 * one that narrows. Every picker over a list FAM already holds was doing that.
 */

const itemToString = (item: { name: string }) => item.name;
const cariboo = { name: "Cariboo-Chilcotin Natural Resource District" };

describe("matchesTypedText", () => {
    it("matches on any part of the name, not just the start", () => {
        // People search for the distinctive part, which is rarely the first
        // word - every district's name ends "Natural Resource District".
        expect(
            matchesTypedText({ item: cariboo, itemToString, inputValue: "chilcotin" })
        ).toBe(true);
    });

    it("ignores case and surrounding space", () => {
        expect(
            matchesTypedText({ item: cariboo, itemToString, inputValue: "  CARIBOO " })
        ).toBe(true);
    });

    it("drops what does not match", () => {
        expect(
            matchesTypedText({ item: cariboo, itemToString, inputValue: "skeena" })
        ).toBe(false);
    });

    it("keeps the whole list when nothing has been typed", () => {
        // Clearing the box brings the list back rather than emptying it.
        for (const inputValue of ["", "   ", null]) {
            expect(matchesTypedText({ item: cariboo, itemToString, inputValue })).toBe(
                true
            );
        }
    });

    it("falls back to the item's own text when no formatter is given", () => {
        // Carbon's type allows itemToString to be absent. Filtering everything
        // out in that case would empty a picker for want of a formatter.
        expect(
            matchesTypedText({ item: "Skeena", inputValue: "kee" })
        ).toBe(true);
    });
});

describe("matchesTypedTextBeside", () => {
    const skeena = { name: "Skeena" };

    it("shows the whole list when the box still holds the selection", () => {
        /*
            Carbon leaves the chosen item's text in the box. Filtering on it
            would narrow the list to the thing already chosen the moment it was
            reopened, and the picker would look like it held one application.
        */
        const filter = matchesTypedTextBeside("Cariboo-Chilcotin Natural Resource District");

        expect(
            filter({
                item: skeena,
                itemToString,
                inputValue: "Cariboo-Chilcotin Natural Resource District",
            })
        ).toBe(true);
    });

    it("filters again as soon as the text is edited", () => {
        const filter = matchesTypedTextBeside("Cariboo-Chilcotin Natural Resource District");

        expect(filter({ item: skeena, itemToString, inputValue: "cariboo" })).toBe(
            false
        );
        expect(filter({ item: skeena, itemToString, inputValue: "skee" })).toBe(true);
    });

    it("filters normally when nothing is selected yet", () => {
        const filter = matchesTypedTextBeside(null);

        expect(filter({ item: skeena, itemToString, inputValue: "cariboo" })).toBe(
            false
        );
    });
});

