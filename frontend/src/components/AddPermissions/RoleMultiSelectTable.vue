<script setup lang="ts">
import Chip from "@/components/UI/Chip.vue";
import type { RoleOption } from "@/views/AddAppPermission/utils";
import Checkbox from "primevue/checkbox";
import Column from "primevue/column";
import DataTable from "primevue/datatable";

/**
 * Choosing several roles, with no scope pickers in the rows.
 *
 * Deliberately not RoleSelectTable with checkboxes. That one nests the district
 * and organisation pickers inside the row's Description cell, which is already
 * tight for one role; with several open at once the rows jump around as tables
 * grow inside them and the list stops being scannable. Choosing is separated
 * from configuring here: this stays a flat list, and each chosen role gets its
 * own card below.
 *
 * The scope requirement is still shown per row - as a tag - because it is what
 * tells somebody a further choice is coming.
 */
const props = defineProps<{
    roleOptions: RoleOption[];
    selectedRoleNames: string[];
    /** Toggles one role. The parent owns the list, including its order. */
    onToggle: (role: RoleOption) => void;
}>();

const isSelected = (role: RoleOption) =>
    props.selectedRoleNames.includes(role.name);

/** What a role has to be narrowed by, in the words the picker uses. */
const scopeTags = (role: RoleOption): string[] => {
    const tags: string[] = [];
    if (role.role_type_district) {
        tags.push("Per district");
    }
    if (role.role_type_client) {
        tags.push("Per organization");
    }
    return tags;
};
</script>

<template>
    <DataTable class="fam-table role-multi-select-table" :value="roleOptions">
        <template #empty>No role available</template>

        <Column header="" class="pick-col">
            <template #body="{ data }">
                <Checkbox
                    class="fam-checkbox"
                    :binary="true"
                    :model-value="isSelected(data)"
                    :aria-label="data.display_name ?? data.name"
                    @change="onToggle(data)"
                />
            </template>
        </Column>

        <Column header="Role">
            <template #body="{ data }">
                <!-- The short name, falling back to the code. -->
                {{ data.display_name ?? data.name }}
            </template>
        </Column>

        <Column header="Description">
            <template #body="{ data }">
                {{ data.description ?? "—" }}
            </template>
        </Column>

        <Column header="Scope">
            <template #body="{ data }">
                <span v-if="!scopeTags(data).length" class="no-scope">
                    Whole application
                </span>
                <span v-else class="scope-tags">
                    <Chip
                        v-for="tag in scopeTags(data)"
                        :key="tag"
                        color="green"
                        :label="tag"
                    />
                </span>
            </template>
        </Column>
    </DataTable>
</template>

<style lang="scss">
.role-multi-select-table {
    .pick-col {
        width: 3rem;
    }

    .scope-tags {
        display: inline-flex;
        flex-wrap: wrap;
        gap: 0.25rem;
    }

    .no-scope {
        color: var(--semantic-color-text-secondary);
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

    .p-datatable-emptymessage {
        background-color: var(--semantic-color-surface-layer-1);
    }
}
</style>
