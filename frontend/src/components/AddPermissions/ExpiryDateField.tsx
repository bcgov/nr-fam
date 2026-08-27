import { DatePicker, DatePickerInput } from "@carbon/react";
import type { FC } from "react";
import "./ExpiryDateField.css";

/**
 * When a grant should end, or nothing for one that should not.
 *
 * <p>Optional on purpose, and blank by default: most access is not temporary,
 * and a date pre-filled with today would quietly turn every grant into one that
 * lapses tonight.
 *
 * <p>The date is the <em>last day</em> the access is good for - it lasts to the
 * end of it - which is how the legacy application read the same field. Saying so
 * under the field rather than in a tooltip, because "expires on the 30th" is
 * ambiguous enough that people would otherwise pick the day before.
 */
type Props = {
    /** YYYY-MM-DD, or "" for access that does not expire. */
    value: string;
    onChange: (value: string) => void;
    invalidText?: string;
};

/** Today where the person is, which is the earliest date worth offering. */
const todayIso = (): string => {
    const now = new Date();
    return [
        now.getFullYear(),
        String(now.getMonth() + 1).padStart(2, "0"),
        String(now.getDate()).padStart(2, "0"),
    ].join("-");
};

export const ExpiryDateField: FC<Props> = ({ value, onChange, invalidText }) => (
    <div className="expiry-date-field">
        <DatePicker
            datePickerType="single"
            dateFormat="Y-m-d"
            /*
                No past dates offered at all. The backend refuses them, and a
                calendar that lets somebody choose one only to be told no
                afterwards is a worse way to say the same thing.
            */
            minDate={todayIso()}
            value={value}
            onChange={(dates: Date[]) => {
                const [picked] = dates;
                if (!picked) {
                    onChange("");
                    return;
                }
                // Built from the local parts rather than toISOString(), which
                // converts to UTC first and hands back the previous day for
                // anybody west of Greenwich - which is everybody here.
                onChange(
                    [
                        picked.getFullYear(),
                        String(picked.getMonth() + 1).padStart(2, "0"),
                        String(picked.getDate()).padStart(2, "0"),
                    ].join("-")
                );
            }}
        >
            <DatePickerInput
                id="expiry-date"
                labelText="Expiry date (optional)"
                placeholder="yyyy-mm-dd"
                size="md"
                invalid={Boolean(invalidText)}
                invalidText={invalidText}
                helperText={
                    invalidText
                        ? undefined
                        : "Access lasts to the end of the day chosen. Leave blank for access that does not expire."
                }
            />
        </DatePicker>

        {value ? (
            <button
                type="button"
                className="expiry-date-field__clear"
                onClick={() => onChange("")}
            >
                Clear, so this access does not expire
            </button>
        ) : null}
    </div>
);

export default ExpiryDateField;
