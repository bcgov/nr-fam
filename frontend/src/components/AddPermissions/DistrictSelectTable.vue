<script setup lang="ts">
import { AppActlApiService } from "@/services/ApiServiceFactory";
import { useQuery } from "@tanstack/vue-query";
import type { FamDistrictDto } from "fam-api";
import Checkbox from "primevue/checkbox";
import Column from "primevue/column";
import DataTable from "primevue/datatable";
import { Field, useField } from "vee-validate";
import { computed, watch } from "vue";
import ErrorText from "../UI/ErrorText.vue";
import Label from "../UI/Label.vue";
import SubsectionTitle from "../UI/SubsectionTitle.vue";

/**
 * District picker, bound to whichever field it is pointed at.
 *
 * It used to read {@code formValues.districts} and write to the literal field
 * name "districts" while <em>also</em> taking a fieldId, so the two could not
 * disagree and only one of these could exist on a screen. Appointing a delegated
 * admin needs one per role - each role they may grant carries its own districts -
 * so both the read and the write go through {@link fieldId} now.
 */
const props = withDefaults(
    defineProps<{
        /** Form path this picker owns, e.g. `districts` or `roles[0].districts`. */
        fieldId: string;
        selected: FamDistrictDto[];
        setFieldValue: (field: string, value: any) => void;
        /**
         * Wording, because the same picker answers two different questions: what
         * a person is being given, or what they may hand out.
         */
        title?: string;
        subtitle?: string;
    }>(),
    {
        title: "Restrict access by districts",
        subtitle: "Select one or more districts for this access",
    }
);

const { validate: validateDistricts, setErrors: setDistrictsError } = useField(
    props.fieldId
);

const districtsQuery = useQuery({
    queryKey: ["districts"],
    queryFn: () =>
        AppActlApiService.districtsApi.getDistricts().then((res) => res.data),
    refetchOnMount: true,
});

watch(
    () => districtsQuery.isError.value,
    (isError) => {
        if (isError) {
            setDistrictsError(
                "Failed to fetch available districts. Please try again."
            );
        }
    }
);

/**
 * Expired districts are kept out of the picker so they cannot be granted, while
 * remaining valid on permissions that already reference them.
 */
const availableDistricts = computed<FamDistrictDto[]>(
    () => districtsQuery.data.value?.filter((d) => !d.expired) ?? []
);

const selectedDistricts = computed<FamDistrictDto[]>(
    () => props.selected ?? []
);

const isDistrictSelected = (district: FamDistrictDto) =>
    selectedDistricts.value.some(
        (selected) => selected.org_unit_code === district.org_unit_code
    );

const toggleDistrict = (district: FamDistrictDto) => {
    const updated = [...selectedDistricts.value];
    const index = updated.findIndex(
        (selected) => selected.org_unit_code === district.org_unit_code
    );
    if (index >= 0) {
        updated.splice(index, 1);
    } else {
        updated.push(district);
    }
    props.setFieldValue(props.fieldId, updated);
    validateDistricts();
};
</script>

<template>
    <div class="district-select-table-container">
        <SubsectionTitle :title="props.title" :subtitle="props.subtitle" />

        <Field
            :name="props.fieldId"
            v-slot="{ errorMessage }"
            :model-value="selectedDistricts"
            @update:model-value="
                (value) => props.setFieldValue(props.fieldId, value)
            "
        >
            <Label label-text="Districts" required />

            <ErrorText v-if="errorMessage" show-icon :error-msg="errorMessage" />

            <DataTable class="fam-table" :value="availableDistricts">
                <template #empty>No district available</template>

                <Column header="">
                    <template #body="{ data }">
                        <Checkbox
                            class="fam-checkbox"
                            :binary="true"
                            :model-value="isDistrictSelected(data)"
                            @change="toggleDistrict(data)"
                        />
                    </template>
                </Column>

                <Column header="Name" field="org_unit_name" />

                <Column header="District code" field="org_unit_code" />
            </DataTable>
        </Field>
    </div>
</template>

<style lang="scss">
.district-select-table-container {
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
