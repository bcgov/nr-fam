<script setup lang="ts">
import {
    isClientScopedRoleSelected,
    isDistrictScopedRoleSelected,
    type AppPermissionFormType,
    type RoleOption,
} from "@/views/AddAppPermission/utils";
import Column from "primevue/column";
import DataTable from "primevue/datatable";
import RadioButton from "primevue/radiobutton";
import { Field } from "vee-validate";
import { computed } from "vue";
import DistrictSelectTable from "./DistrictSelectTable.vue";
import ForestClientAddTable from "./ForestClientAddTable.vue";
import ErrorText from "../UI/ErrorText.vue";
import SubsectionTitle from "../UI/SubsectionTitle.vue";

/**
 * Role selection, with the scope picker for the chosen role nested beneath it.
 *
 * The delegated-admin row this table used to carry is gone: delegated
 * administration moved to CSS along with the table that recorded it, so every
 * row here is a plain CSS role.
 */
const props = defineProps<{
    environment: string;
    roleOptions: RoleOption[];
    roleFieldId: string;
    forestClientsFieldId: string;
    districtsFieldId: string;
    setFieldValue: (field: string, value: any) => void;
    formValues: AppPermissionFormType;
}>();

const selectedRoleName = computed(() => props.formValues.role?.name ?? null);

const selectRole = (role: RoleOption) => {
    props.setFieldValue("role", role);
    // Scope selections belong to the previous role, so they are cleared rather
    // than carried across - a district is meaningless on a client-scoped role.
    props.setFieldValue("forestClients", []);
    props.setFieldValue("districts", []);
};
</script>

<template>
    <div class="role-select-table-container">
        <SubsectionTitle
            title="Assign a role"
            subtitle="Select a role for this permission"
        />

        <Field :name="props.roleFieldId" v-slot="{ errorMessage }">
            <ErrorText v-if="errorMessage" show-icon :error-msg="errorMessage" />

            <DataTable class="fam-table" :value="props.roleOptions">
                <template #empty>No role available</template>

                <Column header="">
                    <template #body="{ data }">
                        <RadioButton
                            :model-value="selectedRoleName"
                            :value="data.name"
                            @change="selectRole(data)"
                        />
                    </template>
                </Column>

                <Column header="Role" field="display_name" />

                <Column header="Description">
                    <template #body="{ data }">
                        <span>{{ data.description ?? "—" }}</span>

                        <DistrictSelectTable
                            v-if="
                                selectedRoleName === data.name &&
                                isDistrictScopedRoleSelected(props.formValues)
                            "
                            :field-id="props.districtsFieldId"
                            :form-values="props.formValues"
                            :set-field-value="props.setFieldValue"
                        />

                        <ForestClientAddTable
                            v-else-if="
                                selectedRoleName === data.name &&
                                isClientScopedRoleSelected(props.formValues)
                            "
                            :environment="props.environment"
                            :field-id="props.forestClientsFieldId"
                            :form-values="props.formValues"
                            :set-field-value="props.setFieldValue"
                        />
                    </template>
                </Column>
            </DataTable>
        </Field>
    </div>
</template>

<style lang="scss">
.role-select-table-container {
    .fam-table {
        .p-datatable-emptymessage {
            background-color: var(--semantic-color-surface-layer-1);
        }
    }
}
</style>
