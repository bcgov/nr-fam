<script setup lang="ts">
import Button from "@/components/UI/Button.vue";
import { FOREST_CLIENT_SEARCH_MIN_LENGTH } from "@/constants/constants";
import { AppActlApiService } from "@/services/ApiServiceFactory";
import { getAxiosErrorStatus } from "@/utils/ApiUtils";
import type { TextInputType } from "@/types/InputTypes";
import type { FamForestClientDto } from "fam-api";
import TrashIcon from "@carbon/icons-vue/es/trash-can/16";
import Column from "primevue/column";
import DataTable from "primevue/datatable";
import AutoComplete from "primevue/autocomplete";
import { Field, useField } from "vee-validate";
import { ref } from "vue";
import Chip from "../UI/Chip.vue";
import HelperText from "../UI/HelperText.vue";
import Label from "../UI/Label.vue";
import SubsectionTitle from "../UI/SubsectionTitle.vue";
import { HttpStatusCode } from "axios";
import FCServiceUnavailableNotification from "../NotificationContent/ServiceUnavailableNtfnTemplate.vue";

/** The search box's own state: what is typed, and whether it is complaining. */
export type ForestClientInput = TextInputType & { isVerifying: boolean };

/**
 * Organisation picker, bound to whichever fields it is pointed at.
 *
 * Both the chosen organisations and the search box's own state used to be read
 * from fixed keys, so two of these on one screen would have shared a search box:
 * typing in one would echo in the other. Appointing a delegated admin needs one
 * per client-scoped role, so both travel as paths now.
 */
const props = withDefaults(
    defineProps<{
        environment: string;
        /** Form path holding the chosen organisations. */
        fieldId: string;
        selected: FamForestClientDto[];
        /** Form path holding this picker's own search-box state. */
        inputFieldId: string;
        input: ForestClientInput;
        setFieldValue: (field: string, value: any) => void;
        /**
         * The only client numbers this caller may grant for, or null when they
         * are not restricted.
         *
         * The search still runs against the whole Forest Client API - it is how
         * a number becomes a name - but anything outside the delegation is
         * dropped from the suggestions. Offering it would be offering a grant
         * the backend refuses.
         */
        allowed?: string[] | null;
        title?: string;
        subtitle?: string;
    }>(),
    {
        title: "Restrict access by organizations",
        subtitle:
            "Add one or more organizations for this user to have access to",
    }
);

const { setErrors: setForestClientsError } = useField(props.fieldId);

// State for Forest Client Service down warning
const isForestClientServiceDown = ref(false);

const updateForestClientInput = (updates: Partial<ForestClientInput>) => {
    props.setFieldValue(props.inputFieldId, {
        ...props.input,
        ...updates,
    });
};

const setVerificationError = (errorMessage: string) => {
    updateForestClientInput({
        isValid: false,
        errorMsg: errorMessage,
    });
};

const clearVerificationError = () => {
    setForestClientsError("");
    updateForestClientInput({
        isValid: true,
        errorMsg: "",
    });
    isForestClientServiceDown.value = false;
};

/**
 * Suggestions for the organisation field.
 *
 * Typed either way: an eight-digit client number, or a name. The backend
 * classifies the term and searches the right column - the Forest Client API ANDs
 * its criteria, so a name and a number cannot be sent together and one has to be
 * chosen.
 */
const suggestions = ref<FamForestClientDto[]>([]);

const onSearch = async (event: { query: string }) => {
    const term = event.query.trim();
    clearVerificationError();

    // The backend enforces this too; checking here avoids a request that can
    // only come back as a validation error.
    if (term.length < FOREST_CLIENT_SEARCH_MIN_LENGTH) {
        suggestions.value = [];
        return;
    }

    try {
        const { data } =
            await AppActlApiService.forestClientsApi.autocompleteForestClients(
                term,
                props.environment
            );

        // Already-added organisations are dropped rather than offered and then
        // refused on selection.
        const chosen = new Set(
            props.selected.map(
                (client) => client.forest_client_number
            )
        );
        // And anything outside the delegation, for the same reason: the grant
        // path compares the scoped role name, so a client the delegation does
        // not name is refused however it was chosen.
        const permitted = props.allowed == null ? null : new Set(props.allowed);

        suggestions.value = data.filter(
            (client) =>
                !chosen.has(client.forest_client_number) &&
                (permitted === null ||
                    permitted.has(client.forest_client_number))
        );
        isForestClientServiceDown.value = false;
    } catch (error) {
        suggestions.value = [];
        if (getAxiosErrorStatus(error) === HttpStatusCode.GatewayTimeout) {
            isForestClientServiceDown.value = true;
        } else {
            setVerificationError(
                "The organization search failed. Please try again"
            );
        }
    }
};

/**
 * Adds the chosen organisation.
 *
 * The status check stays even though the search now returns only active
 * organisations: it is a backstop against a suggestion that has gone stale
 * between the search and the click, not the reason inactive ones are hidden.
 */
const onSelect = (event: { value: FamForestClientDto }) => {
    const client = event.value;

    if (client.status?.status_code !== "A") {
        setVerificationError(
            "This organization can't be added due to its status"
        );
    } else {
        props.setFieldValue(props.fieldId, [
            ...props.selected,
            client,
        ]);
    }
    // Cleared either way: the field is a search box, not a value.
    updateForestClientInput({ value: "" });
};

const removeForestClientFromList = (clientNumber: string) => {
    props.setFieldValue(
        props.fieldId,
        props.selected.filter(
            (client) => client.forest_client_number !== clientNumber
        )
    );
};

</script>

<template>
    <div class="foresnt-client-add-table-container">
        <SubsectionTitle :title="props.title" :subtitle="props.subtitle" />

        <FCServiceUnavailableNotification
            v-if="isForestClientServiceDown"
            message="Forest Client Service is unavailable. Role with forest client cannot be added."
        />

        <Label
            :for="props.input.id"
            label-text="Organization"
            required
        />
        <Field
            :name="props.fieldId"
            v-slot="{ errorMessage }"
            :model-value="props.selected"
            @update:model-value="(value) => props.setFieldValue(props.fieldId, value)"
        >
            <!--
                One field, either kind of term. `option-label` is what lands in
                the box on selection; the slot below is what the list shows,
                because a number alone is not something anybody recognises.
            -->
            <AutoComplete
                :id="props.input.id"
                class="w-100 forest-client-autocomplete"
                :model-value="props.input.value"
                @update:model-value="
                    (value: any) =>
                        updateForestClientInput({
                            value: typeof value === 'string' ? value : '',
                        })
                "
                :suggestions="suggestions"
                option-label="client_name"
                :min-length="FOREST_CLIENT_SEARCH_MIN_LENGTH"
                :delay="300"
                :complete-on-focus="false"
                placeholder="Search by organization name or client number"
                :invalid="!!(errorMessage || !props.input.isValid)"
                @complete="onSearch"
                @item-select="onSelect"
            >
                <template #option="{ option }">
                    <span class="option-name">{{ option.client_name }}</span>
                    <!--
                        The separator is inside the interpolation, not template
                        text. Vue condenses whitespace between and around nodes,
                        so a leading space written in the markup is dropped and
                        the dash ends up against the name; inside the expression
                        it survives. It is real text rather than a ::before so
                        it is copied and announced with the option.
                    -->
                    <span class="option-number">{{
                        ` - ${option.forest_client_number}`
                    }}</span>
                </template>

                <template #empty>No organization found</template>
            </AutoComplete>

            <HelperText
                :text="
                    errorMessage ||
                    props.input.errorMsg ||
                    (props.allowed != null
                        ? `You may grant this role for ${props.allowed.length} organization(s). Search to find them.`
                        : 'Type an organization name or client number, then choose from the list')
                "
                :is-error="
                    !!(
                        errorMessage ||
                        !props.input.isValid
                    )
                "
            />
        </Field>

        <!-- Table section -->
        <DataTable class="fam-table" :value="props.selected">
            <template #empty>No organization added yet</template>

            <Column header="Client number" field="forest_client_number" />

            <Column header="Name" field="client_name" />

            <Column header="Status">
                <template #body="{ data }">
                    <Chip
                        v-tooltip.top="
                            'Current status of this organization in the Client Management System'
                        "
                        color="green"
                        :label="data.status.description"
                    />
                </template>
            </Column>

            <Column header="Action">
                <template #body="{ data }">
                    <Button
                        icon-only
                        :icon="TrashIcon"
                        @click="
                            removeForestClientFromList(
                                data.forest_client_number
                            )
                        "
                    />
                </template>
            </Column>
        </DataTable>
    </div>
</template>

<style lang="scss">
/*
    Outside the component's container on purpose.

    AutoComplete's appendTo defaults to "body", so the overlay - and every
    option in it - is teleported out of this component's DOM. Rules nested
    under the container never matched it, which is why the number showed in
    the default colour with no gap however the markup was written.

    Scoped to PrimeVue's own overlay class rather than left bare, so
    .option-number does not become a global.
*/
.p-autocomplete-overlay {
    .option-number {
        /*
            The margin is what separates the name from the dash. The text also
            carries a leading space, but an option is a flex row and a flex
            item's leading whitespace is trimmed - the span measures 4px
            narrower - so the space survives a copy but never reaches the
            screen. Both are needed: the margin for the eye, the space for
            anything reading or copying the text.
        */
        margin-left: 0.25rem;
        color: var(--semantic-color-text-secondary);
    }

}

.foresnt-client-add-table-container {
    .forest-client-autocomplete {
        max-width: 32rem;

        .p-autocomplete-input {
            width: 100%;
        }
    }

    .subsection-title-container {
        margin: 1.5rem 0;
    }

    .input-with-verify-button {
        .add-organization-button {
            width: 12rem;
        }
    }

    .fam-table {
        margin-top: 1.5rem;

        .p-datatable-emptymessage {
            background-color: var(--semantic-color-surface-layer-1);
        }
    }
}
</style>
