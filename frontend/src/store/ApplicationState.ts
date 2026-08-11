import { type CssApplicationOptionDto } from "fam-api";
import { ref } from "vue";

/**
 * The application being administered, sourced from CSS.
 *
 * Identified by the pair (integration_id, environment): a CSS integration spans
 * environments, where what FAM calls an application does not.
 */
export const selectedApp = ref<CssApplicationOptionDto>();

export const setSelectedApp = (app: CssApplicationOptionDto) => {
    selectedApp.value = app;
};
