<script setup lang="ts">
import Button from "@/components/UI/Button.vue";
import { FOREST_CLIENT_SEARCH_MIN_LENGTH } from "@/constants/constants";
import { AppActlApiService } from "@/services/ApiServiceFactory";
import { getAxiosErrorStatus } from "@/utils/ApiUtils";
import type { AppPermissionFormType } from "@/views/AddAppPermission/utils";
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

const props = defineProps<{
    environment: string;
    fieldId: string;
    formValues: AppPermissionFormType;
    setFieldValue: (field: string, value: any) => void;
}>();

const { setErrors: setForestClientsError } = useField(props.fieldId);

// State for Forest Client Service down warning
const isForestClientServiceDown = ref(false);

const updateForestClientInput = (
    updates: Partial<AppPermissionFormType["forestClientInput"]>
) => {
    props.setFieldValue("forestClientInput", {
        ...props.formValues.forestClientInput,
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
            props.formValues.forestClients.map(
                (client) => client.forest_client_number
            )
        );
        suggestions.value = data.filter(
            (client) => !chosen.has(client.forest_client_number)
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
 * The status check is the one the verify button used to make: an inactive
 * organisation is findable but not grantable, and saying so is more use than
 * hiding it from the results.
 */
const onSelect = (event: { value: FamForestClientDto }) => {
    const client = event.value;

    if (client.status?.status_code !== "A") {
        setVerificationError(
            "This organization can't be added due to its status"
        );
    } else {
        props.setFieldValue("forestClients", [
            ...props.formValues.forestClients,
            client,
        ]);
    }
    // Cleared either way: the field is a search box, not a value.
    updateForestClientInput({ value: "" });
};

const removeForestClientFromList = (clientNumber: string) => {
    props.setFieldValue(
        "forestClients",
        props.formValues.forestClients.filter(
            (client) => client.forest_client_number !== clientNumber
        )
    );
};

</script>

<template>
    <div class="foresnt-client-add-table-container">
        <SubsectionTitle
            title="Restrict access by organizations"
            subtitle="Add one or more organizations for this user to have access to"
        />

        <FCServiceUnavailableNotification
            v-if="isForestClientServiceDown"
            message="Forest Client Service is unavailable. Role with forest client cannot be added."
        />

        <Label
            for="forestClientInput"
            label-text="Organization"
            required
        />
        <Field
            :name="props.fieldId"
            v-slot="{ errorMessage }"
            :model-value="props.formValues.forestClients"
            @update:model-value="(value) => props.setFieldValue('forestClients', value)"
        >
            <!--
                One field, either kind of term. `option-label` is what lands in
                the box on selection; the slot below is what the list shows,
                because a number alone is not something anybody recognises.
            -->
            <AutoComplete
                :id="props.formValues.forestClientInput.id"
                class="w-100 forest-client-autocomplete"
                :model-value="props.formValues.forestClientInput.value"
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
                :invalid="!!(errorMessage || !props.formValues.forestClientInput.isValid)"
                @complete="onSearch"
                @item-select="onSelect"
            >
                <template #option="{ option }">
                    <span class="option-name">{{ option.client_name }}</span>
                    <span class="option-number">
                        {{ option.forest_client_number }}
                    </span>
                    <!-- Findable but not grantable; say so before it is chosen. -->
                    <span
                        v-if="option.status?.status_code !== 'A'"
                        class="option-inactive"
                    >
                        {{ option.status?.description ?? "Inactive" }}
                    </span>
                </template>

                <template #empty>No organization found</template>
            </AutoComplete>

            <HelperText
                :text="
                    errorMessage ||
                    props.formValues.forestClientInput.errorMsg ||
                    'Type an organization name or client number, then choose from the list'
                "
                :is-error="
                    !!(
                        errorMessage ||
                        !props.formValues.forestClientInput.isValid
                    )
                "
            />
        </Field>

        <!-- Table section -->
        <DataTable class="fam-table" :value="props.formValues.forestClients">
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
.foresnt-client-add-table-container {
    .forest-client-autocomplete {
        max-width: 32rem;

        .p-autocomplete-input {
            width: 100%;
        }
    }

    .option-number {
        margin-left: 0.5rem;
        color: var(--semantic-color-text-secondary);
    }

    .option-inactive {
        margin-left: 0.5rem;
        color: var(--semantic-color-text-error);
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
