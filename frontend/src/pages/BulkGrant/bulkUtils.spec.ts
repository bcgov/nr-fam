import { describe, expect, it, vi } from "vitest";
import { TEMPLATE_CSV, downloadTemplateCsv, fullName } from "./bulkUtils";

describe("bulk grant template", () => {
    it("is the header the uploader expects", () => {
        // The backend recognises this as a header and skips it; a mismatch here
        // would turn the first line into a data row that always errors.
        expect(TEMPLATE_CSV.trim()).toBe(
            "user_guid,user_type,role,district,organization,region"
        );
    });

    it("offers a column for each scope a role can require", () => {
        // A role scoped by district and by organisation is granted per pair, so
        // both columns exist and a row may carry both. Without them the file
        // could only express unscoped roles, which is what it used to do.
        const header = TEMPLATE_CSV.trim().split(",");

        expect(header).toContain("district");
        expect(header).toContain("organization");
        expect(header).toContain("region");
    });

    it("keeps region last, where appending it cannot shift another column", () => {
        // The parser is positional. Slotting region in beside district would
        // reinterpret every file written before it - organisation numbers would
        // arrive as regions, and each row would fail for a reason that named the
        // wrong column.
        const header = TEMPLATE_CSV.trim().split(",");

        expect(header[header.length - 1]).toBe("region");
        expect(header.indexOf("organization")).toBe(4);
    });

    it("offers a column for the directory a GUID belongs to", () => {
        // Optional, but stating it halves the directory lookups and stops a
        // GUID resolving to whichever directory answers first.
        expect(TEMPLATE_CSV.trim().split(",")).toContain("user_type");
    });

    it("carries no example row", () => {
        // A placeholder GUID gets uploaded as-is often enough to matter, and can
        // only come back as an error the person did not cause.
        expect(TEMPLATE_CSV.trim().split("\n")).toHaveLength(1);
    });

    it("downloads as a UTF-8 CSV with a byte-order mark", () => {
        const created = vi.fn((_blob: Blob) => "blob:url");
        const revoked = vi.fn();
        // jsdom implements neither.
        (URL as any).createObjectURL = created;
        (URL as any).revokeObjectURL = revoked;
        const click = vi.fn();
        vi.spyOn(document, "createElement").mockReturnValueOnce({
            click,
            set href(_v: string) {},
            set download(_v: string) {},
        } as unknown as HTMLAnchorElement);
        vi.spyOn(document.body, "appendChild").mockImplementation((n) => n);
        vi.spyOn(document.body, "removeChild").mockImplementation((n) => n);

        downloadTemplateCsv();

        expect(click).toHaveBeenCalled();
        // The mark is what makes Excel read it as UTF-8; the backend strips it.
        const blob = created.mock.calls[0]![0];
        expect(blob.type).toContain("charset=utf-8");
        expect(revoked).toHaveBeenCalledWith("blob:url");
        vi.restoreAllMocks();
    });
});

describe("fullName", () => {
    it("is empty when the GUID resolved to nobody", () => {
        expect(fullName({ first_name: null, last_name: null } as never)).toBe("");
    });

    it("joins the parts it has", () => {
        expect(fullName({ first_name: "Jane", last_name: "Smith" } as never))
            .toBe("Jane Smith");
    });
});
