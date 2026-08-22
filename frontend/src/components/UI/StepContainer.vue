<script setup lang="ts">
import Divider from "@/components/UI/Divider.vue";

defineProps<{
    title?: string;
    subtitle?: string;
    divider?: boolean;
}>();
</script>

<template>
    <div class="step-container">
        <h3 v-if="title" class="title">{{ title }}</h3>
        <p v-if="subtitle" class="subtitle" aria-roledescription="subtitle">
            {{ subtitle }}
        </p>
        <div :class="subtitle ? 'step-content' : ''">
            <slot />
        </div>
        <!--
            The class is what the margin rule below hooks onto. Divider styles
            its own margins from a scoped block, so a one-class selector loses to
            it on specificity and a two-class one at the same weight would depend
            on stylesheet order.
        -->
        <Divider v-if="divider" class="step-divider" />
    </div>
</template>

<style lang="scss">
.step-container {
    container-type: inline-size; // Enables container queries for child components

    /*
        The rule between steps, at 1.25rem a side rather than Divider's own
        2.5rem. The two margins do not collapse against each other, so the
        default cost 5rem of empty page between every pair of steps - and 7rem
        above a button bar, which adds its own.

        Every add-permission screen used to carry its own copy of this. It lives
        here so a new step-based form inherits the spacing instead of repeating
        the override, and so the `hr.solid` specificity trap is sprung once.

        `hr.solid.step-divider` is (0,3,1) against Divider's scoped
        `hr.solid[data-v-...]` at (0,2,1), so it wins on weight rather than on
        the order the bundler happens to emit.
    */
    > hr.solid.step-divider {
        margin-top: 1.25rem;
        margin-bottom: 1.25rem;
    }

    .step-content {
        margin-top: 1.5rem;
    }

    .title {
        @include type.type-style("heading-03");
        color: var(--semantic-color-text-primary);
    }

    .subtitle {
        @include type.type-style("body-01");
        color: var(--semantic-color-text-secondary);
    }
}
</style>
