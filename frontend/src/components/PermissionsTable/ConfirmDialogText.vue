<script setup lang="ts">
/**
 * What a revocation will actually do, in the confirmation.
 *
 * Names the role and the scope as well as the user: an administrator revoking
 * one of several districts needs to see which one they are about to remove.
 */
defineProps<{
    userName: string;
    role: string;
    scope?: string | null;
    appName: string;
}>();
</script>

<template>
    <span>
        Are you sure you want to remove
        <strong>{{ role }}</strong>
        <template v-if="scope">
            for <strong>{{ scope }}</strong>
        </template>
        from <strong>{{ userName }}</strong> in {{ appName }}? They will lose
        that access immediately.
    </span>
</template>
