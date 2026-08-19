<script setup lang="ts">
/**
 * What deleting a role will actually do, in the confirmation.
 *
 * Deleting a role is not only a change to the application's definition: it
 * takes the role away from everyone holding it, in the same act and with no
 * undo. The member count is the part an administrator needs before agreeing, so
 * it is stated plainly rather than left to be discovered afterwards.
 */
defineProps<{
    roleName: string;
    description?: string | null;
    appName: string;
    /** Null while the count is still loading, which is not the same as zero. */
    memberCount?: number | null;
}>();
</script>

<template>
    <span>
        Are you sure you want to delete
        <strong>{{ description || roleName }}</strong>
        from {{ appName }}?

        <template v-if="memberCount === null || memberCount === undefined">
            Anyone currently holding it will lose that access immediately.
        </template>
        <template v-else-if="memberCount === 0">
            Nobody currently holds it.
        </template>
        <template v-else>
            <strong>
                {{ memberCount }}
                {{ memberCount === 1 ? "person holds" : "people hold" }}
            </strong>
            it and will lose that access immediately.
        </template>

        This cannot be undone.
    </span>
</template>
