<script setup lang="ts">
import type { UserGrantOutcome } from "@/views/AddAppPermission/utils";
import { describeUser, failedRoles } from "@/views/ManagePermissionsView/utils";
import CheckMarkIcon from "@carbon/icons-vue/es/checkmark--filled/20";
import DotMarkIcon from "@carbon/icons-vue/es/dot-mark/16";
import { computed, ref } from "vue";

/**
 * "Permission added to the following users", with the users listed.
 *
 * Long lists collapse to the first two behind a show-more, so granting to
 * twenty people does not push the table off the screen.
 */
const props = defineProps<{
    outcomes: UserGrantOutcome[];
    roleName: string;
    applicationName: string;
}>();

const isExpanded = ref(false);
const showToggle = computed(() => props.outcomes.length > 2);

const visibleOutcomes = computed(() =>
    !showToggle.value || isExpanded.value
        ? props.outcomes
        : props.outcomes.slice(0, 2)
);

/**
 * A user who got some of their roles but not all of them.
 *
 * Named here rather than only in the failure banner, so the row that says
 * "granted" does not quietly overstate what they were given.
 */
const partialSuffix = (outcome: UserGrantOutcome): string => {
    const failed = failedRoles(outcome).length;
    return failed > 0 ? ` - ${failed} of their selections could not be added` : "";
};
</script>

<template>
    <div class="success-permission-content">
        <CheckMarkIcon />
        <div class="notification-body">
            <div class="notification-header">
                <strong>Success</strong>: {{ roleName }} added in
                {{ applicationName }}
            </div>

            <button
                v-if="showToggle && isExpanded"
                class="toggle-link"
                type="button"
                @click="isExpanded = false"
            >
                show less...
            </button>

            <ul class="notification-list">
                <li
                    v-for="outcome in visibleOutcomes"
                    :key="outcome.user.userId"
                    class="notification-list-item"
                >
                    <DotMarkIcon class="dot-mark-icon" />
                    <span>{{ describeUser(outcome) }}{{ partialSuffix(outcome) }}</span>
                </li>
            </ul>

            <button
                v-if="showToggle && !isExpanded"
                class="toggle-link"
                type="button"
                @click="isExpanded = true"
            >
                show more...
            </button>
        </div>
    </div>
</template>

<style lang="scss" scoped>
@use "./notification-content" as content;

.success-permission-content {
    @include content.notification-content(var(--semantic-color-support-success));
}
</style>
