import { isAxiosError, type AxiosError } from "axios";

/**
 * Formats an Axios error into a string containing the status and error message.
 *
 * @param {AxiosError} err - The Axios error object.
 * @returns {string} A formatted error string in the format "status: message".
 */
export const formatAxiosError = (err: AxiosError): string => {
    let errMsg = err.message;

    if (err.response) {
        // Use 'any' because we don't have this type exported.
        const detail = (err.response.data as any).detail;

        // Check if detail is an array or an object
        const description = Array.isArray(detail) ? null : detail?.description;

        if (description) {
            errMsg = description ?? err.response.status.toString();
        }
    }
    return errMsg;
};

/**
 * What went wrong, in the words the backend used.
 *
 * <p>The backend answers a refusal with
 * {@code {"detail": {"code", "description"}}} - the description is the sentence
 * that names the actual rule: whose permissions, which organisation, which role.
 * Several callers read {@code data.description} instead, one level too shallow,
 * so every one of them fell through to Axios's own message and showed
 * "Request failed with status code 403" where an explanation belonged.
 *
 * <p>Handles all three shapes the backend produces - see GlobalExceptionHandler:
 * a business error, a validation error (an array of {@code msg}), and an
 * upstream failure ({@code {failureCode, message}}).
 *
 * <p>Axios's own message is used only when there is no response at all, where it
 * says something genuinely useful like "Network Error". A response that came back with a
 * status FAM chose is described by the fallback instead, which is a sentence
 * somebody wrote for that screen.
 */
export const describeApiError = (error: unknown, fallback: string): string => {
    const response = (error as { response?: { data?: unknown } })?.response;

    if (!response) {
        return (error as Error)?.message || fallback;
    }

    const data = response.data as
        | { detail?: unknown; message?: string }
        | undefined;

    const detail = data?.detail;

    if (Array.isArray(detail)) {
        const messages = detail
            .map((item) => (item as { msg?: string })?.msg)
            .filter(Boolean);
        if (messages.length > 0) {
            return messages.join(" ");
        }
    } else if (detail && typeof detail === "object") {
        const description = (detail as { description?: string }).description;
        if (description) {
            return description;
        }
    }

    // The upstream-failure shape, which carries no detail at all.
    if (data?.message) {
        return data.message;
    }

    return fallback;
};

/**
 * Gets the HTTP status code from an unknown error when it is an Axios error.
 *
 * @param {unknown} error - The error from a mutation/query handler.
 * @returns {number | null} The HTTP status code, or null when unavailable.
 */
export const getAxiosErrorStatus = (error: unknown): number | null => {
    if (!isAxiosError(error)) {
        return null;
    }

    return error.response?.status ?? null;
};
