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

/**
 * Extracts unique applications from the AdminUserAccessResponse, filtering by `auth_key`.
 *
 * - Only adds applications with `auth_key === "FAM_ADMIN"` if `id === 1`.
 *
 * @param {AdminUserAccessResponse} data - The response containing user access information.
 * @returns {FamApplicationGrantDto[]} An array of unique FamApplicationGrantDto objects.
 */
