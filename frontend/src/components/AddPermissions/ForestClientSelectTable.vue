<script setup lang="ts">
import type { FamForestClientDto } from "fam-api";
import Checkbox from "primevue/checkbox";
import Column from "primevue/column";
import DataTable from "primevue/datatable";
import { Field, useField } from "vee-validate";
import { computed } from "vue";
import Chip from "../UI/Chip.vue";
import ErrorText from "../UI/ErrorText.vue";
import Label from "../UI/Label.vue";
import SubsectionTitle from "../UI/SubsectionTitle.vue";

/**
 * Organisations as a checkbox list, for a caller who may only grant a few.
 *
 * The search box this replaces is the right shape when the answer could be any
 * of tens of thousands of organisations. A delegated administrator's delegation
 * names a handful, and searching for something you have already been told the
 * whole of is work for no reason - you would type, wait, and find the same five
 * rows every time.
 *
 * Deliberately the same shape as the district picker, which has always been a
 * list for the same reason: a fixed, short, known set.
 *
 * <b>Only rendered when restricted.</b> An application administrator may grant
 * any organisation, so their picker stays a search - see ForestClientAddTable.
 */
const props = withDefaults(
    defineProps<{
        /** Form path this picker owns, e.g. `roles[0].forestClients`. */
        fieldId: string;
        selected: FamForestClientDto[];
        /** Exactly the organisations this caller may grant for. */
        options: FamForestClientDto[];
        setFieldValue: (field: string, value: any) => void;
        title?: string;
        subtitle?: string;
    }>(),
    {
        title: "Organizations",
        subtitle: "Select one or more organizations for this role",
    }
);

const { validate: validateClients } = useField(props.fieldId);

const isSelected = (client: FamForestClientDto) =>
    props.selected.some(
        (chosen) => chosen.forest_client_number === client.forest_client_number
    );

const toggle = (client: FamForestClientDto) => {
    const updated = [...props.selected];
    const index = updated.findIndex(
        (chosen) => chosen.forest_client_number === client.forest_client_number
    );

    if (index >= 0) {
        updated.splice(index, 1);
    } else {
        updated.push(client);
    }
    props.setFieldValue(props.fieldId, updated);
    validateClients();
};

/** Nothing to choose: the delegation named none, or none of them is active. */
const nothingToOffer = computed(() => props.options.length === 0);
</script>

<template>
    <div class="forest-client-select-table-container">
        <SubsectionTitle :title="props.title" :subtitle="props.subtitle" />

        <Field
            :name="props.fieldId"
            v-slot="{ errorMessage }"
            :model-value="props.selected"
            @update:model-value="
                (value) => props.setFieldValue(props.fieldId, value)
            "
        >
            <Label label-text="Organizations" required />

            <ErrorText v-if="errorMessage" show-icon :error-msg="errorMessage" />

            <DataTable class="fam-table" :value="props.options">
                <template #empty>
                    <!--
                        Said plainly rather than left blank. An empty table with
                        no explanation reads as a screen that failed to load.
                    -->
                    {{
                        nothingToOffer
                            ? "You have not been delegated any organization for this role"
                            : "No organization available"
                    }}
                </template>

                <Column header="">
                    <template #body="{ data }">
                        <Checkbox
                            class="fam-checkbox"
                            :binary="true"
                            :model-value="isSelected(data)"
                            :aria-label="data.client_name ?? data.forest_client_number"
                            @change="toggle(data)"
                        />
                    </template>
                </Column>

                <Column header="Client number" field="forest_client_number" />

                <Column header="Name">
                    <template #body="{ data }">
                        {{ data.client_name ?? "—" }}
                    </template>
                </Column>

                <Column header="Status">
                    <template #body="{ data }">
                        <Chip
                            v-if="data.status"
                            color="green"
                            :label="data.status.description"
                        />
                    </template>
                </Column>
            </DataTable>
        </Field>
    </div>
</template>

<style lang="scss">
.forest-client-select-table-container {
    .error-text-container {
        padding: 0;
        height: fit-content;
        margin-bottom: 0.5rem;
    }

    .subsection-title-container {
        margin: 1.5rem 0;
    }

    .fam-table {
        .p-datatable-emptymessage {
            background-color: var(--semantic-color-surface-layer-1);
        }
    }

    .fam-checkbox {
        display: flex;
        flex-direction: row;
        align-items: center;

        .p-checkbox-box {
            width: 1rem;
            height: 1rem;
        }
    }
}
</style>
