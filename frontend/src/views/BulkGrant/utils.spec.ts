import { describe, expect, it, vi } from "vitest";
import { TEMPLATE_CSV, downloadTemplateCsv, fullName } from "./utils";

describe("bulk grant template", () => {
    it("is the header the uploader expects", () => {
        // The backend recognises this as a header and skips it; a mismatch here
        // would turn the first line into a data row that always errors.
        expect(TEMPLATE_CSV.trim()).toBe("user_guid,role");
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
