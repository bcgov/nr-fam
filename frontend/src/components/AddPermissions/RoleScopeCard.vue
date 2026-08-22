<script setup lang="ts">
import Chip from "@/components/UI/Chip.vue";
import {
    MAX_SCOPE_COMBINATIONS,
    roleLabel,
    scopeCombinationCount,
    type RoleScopeSelection,
} from "@/utils/ScopeUtils";
import { computed } from "vue";
import DistrictSelectTable from "./DistrictSelectTable.vue";
import ForestClientAddTable from "./ForestClientAddTable.vue";

/**
 * One chosen role, and what it is scoped to.
 *
 * A card per role rather than one shared pair of pickers, because the scope
 * belongs to the role: two roles chosen together can be narrowed differently,
 * and a district chosen for one says nothing about the other. The card is what
 * makes that ownership visible - the pickers sit inside the role they belong to
 * rather than floating beneath a list.
 *
 * The count in the header is the point of the card. A role scoped by district
 * and organisation applies per <em>pair</em>, so three districts and two
 * organisations is six. Nothing else on the form says that, and it is what runs
 * into the backend's ceiling.
 *
 * Shared by the grant screen and the delegated-admin screen. Only the wording
 * differs - what a person is being given, or what they may hand out - so it
 * arrives as props rather than being branched on here.
 */
const props = withDefaults(
    defineProps<{
        selection: RoleScopeSelection;
        /** Form path of this role's entry, e.g. `roles[0]`. */
        fieldPath: string;
        environment: string;
        setFieldValue: (field: string, value: any) => void;
        onRemove: () => void;
        districtTitle?: string;
        districtSubtitle?: string;
        clientTitle?: string;
        clientSubtitle?: string;
        /** Singular noun for the count, e.g. "delegation" or "permission". */
        countNoun?: string;
    }>(),
    {
        districtTitle: "Districts",
        districtSubtitle: "Select one or more districts for this role",
        clientTitle: "Organizations",
        clientSubtitle: "Add one or more organizations for this role",
        countNoun: "permission",
    }
);

const count = computed(() => scopeCombinationCount(props.selection));

const overTheLimit = computed(() => count.value > MAX_SCOPE_COMBINATIONS);

/** Zero once a scoped role has been chosen but nothing selected for it yet. */
const countLabel = computed(() =>
    count.value === 1
        ? `1 ${props.countNoun}`
        : `${count.value} ${props.countNoun}s`
);
</script>

<template>
    <div class="role-scope-card">
        <div class="card-header">
            <Chip :label="roleLabel(selection.role)" />

            <span
                class="scope-count"
                :class="{ 'over-limit': overTheLimit }"
            >
                {{ countLabel }}
            </span>

            <button
                type="button"
                class="remove-role"
                :aria-label="`Remove ${roleLabel(selection.role)}`"
                @click="onRemove"
            >
                Remove
            </button>
        </div>

        <p v-if="overTheLimit" class="limit-warning">
            <!--
                Said before the request rather than after: the backend refuses
                anything past this, and finding out on submit means re-doing the
                whole selection.
            -->
            That is more than the {{ MAX_SCOPE_COMBINATIONS }} one role can
            carry. Narrow the selection below.
        </p>

        <DistrictSelectTable
            v-if="selection.role.role_type_district"
            :field-id="`${fieldPath}.districts`"
            :selected="selection.districts"
            :set-field-value="setFieldValue"
            :title="districtTitle"
            :subtitle="districtSubtitle"
        />

        <!--
            v-if, not v-else-if: a role may require a district AND a forest
            client, and it applies to each pair. Chained, the second picker
            would never render while validation still demanded a value for it - a
            form that cannot be submitted and does not say why.
        -->
        <ForestClientAddTable
            v-if="selection.role.role_type_client"
            :environment="environment"
            :field-id="`${fieldPath}.forestClients`"
            :selected="selection.forestClients"
            :input-field-id="`${fieldPath}.forestClientInput`"
            :input="selection.forestClientInput"
            :set-field-value="setFieldValue"
            :title="clientTitle"
            :subtitle="clientSubtitle"
        />
    </div>
</template>

<style lang="scss">
.role-scope-card {
    border: 1px solid var(--border-strong-01, #dfdfe1);
    border-radius: 4px;
    padding: 1rem 1.25rem 1.25rem;
    margin-bottom: 1rem;

    .card-header {
        display: flex;
        align-items: center;
        gap: 0.75rem;
    }

    .scope-count {
        color: var(--semantic-color-text-secondary);
        font-size: 0.875rem;

        &.over-limit {
            color: var(--semantic-color-text-error, #d8292f);
            font-weight: 700;
        }
    }

    /* Pushed to the far end, so it is never mistaken for part of the count. */
    .remove-role {
        margin-left: auto;
        background: none;
        border: none;
        padding: 0.25rem;
        cursor: pointer;
        color: var(--semantic-color-link-primary);
        text-decoration: underline;
    }

    .limit-warning {
        margin-top: 0.75rem;
        color: var(--semantic-color-text-error, #d8292f);
    }

    /*
        The pickers space themselves for a full-width step. Inside a card they
        only need separating from the header above them.
    */
    .subsection-title-container {
        margin: 1rem 0 0.75rem;
    }
}
</style>
