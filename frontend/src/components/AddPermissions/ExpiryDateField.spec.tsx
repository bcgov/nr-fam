import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { ExpiryDateField } from "./ExpiryDateField";

describe("ExpiryDateField", () => {
    it("accepts a date in the format it displays", () => {
        // Carbon's input defaults to an mm/dd/yyyy pattern, which the browser
        // enforces on submit - a held yyyy-mm-dd expiry was refused with
        // "Please match the requested format".
        render(<ExpiryDateField value="2099-03-31" onChange={() => undefined} />);

        const input = screen.getByLabelText(
            "Expiry date (optional)"
        ) as HTMLInputElement;
        expect(input.value).toBe("2099-03-31");
        expect(input.validity.patternMismatch).toBe(false);
    });
});
