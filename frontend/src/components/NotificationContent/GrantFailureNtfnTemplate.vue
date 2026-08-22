<script setup lang="ts">
import type { UserGrantOutcome } from "@/views/AddAppPermission/utils";
import { roleLabel } from "@/utils/ScopeUtils";
import { describeUser, failedRoles } from "@/views/ManagePermissionsView/utils";
import DotMarkIcon from "@carbon/icons-vue/es/dot-mark/16";
import MisuseIcon from "@carbon/icons-vue/es/misuse/20";
import { computed, ref } from "vue";

/**
 * The users a grant did not reach, and why.
 *
 * The reason comes from the backend rather than being generalised here: it is
 * the difference between "that user is at another organisation" and "the
 * directory is down", and an administrator needs to know which they are looking
 * at.
 */
/**
 * The role is per outcome now, not per banner: one grant can name several roles
 * and they do not share a fate, so "FREP_EDITOR was not added for these users"
 * was only ever true of one of them.
 */
const props = defineProps<{
    outcomes: UserGrantOutcome[];
    applicationName: string;
}>();

const isExpanded = ref(false);
const showToggle = computed(() => props.outcomes.length > 2);

const visibleOutcomes = computed(() =>
    !showToggle.value || isExpanded.value
        ? props.outcomes
        : props.outcomes.slice(0, 2)
);

const reason = (outcome: UserGrantOutcome): string => {
    if (outcome.error) {
        return outcome.error;
    }
    const messages = failedRoles(outcome)
        .map((result) => result.error_message)
        .filter(Boolean);
    return messages.length > 0
        ? (messages.join("; ") as string)
        : "the role could not be assigned";
};
</script>

<template>
    <div class="failed-permission-content">
        <MisuseIcon />
        <div class="notification-body">
            <div class="notification-header">
                <strong>Failed</strong>: some permissions were not added in
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
                    :key="`${outcome.user.userId}|${outcome.role.name}`"
                    class="notification-list-item"
                >
                    <DotMarkIcon class="dot-mark-icon" />
                    <span>
                        {{ describeUser(outcome) }} -
                        {{ roleLabel(outcome.role) }} - {{ reason(outcome) }}
                    </span>
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

.failed-permission-content {
    @include content.notification-content(var(--semantic-color-support-error));
}
</style>
