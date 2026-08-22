<script setup lang="ts">
import type { AdminRoleAuthGroup } from "fam-api";

/**
 * What removing an administrator will actually do, in the confirmation.
 *
 * The two tiers lose different things and the wording says so. An application
 * administrator loses authority over the whole application; a delegated
 * administrator loses the right to grant one role, and keeps any others they
 * were delegated - each is its own row, and removing one leaves the rest.
 */
defineProps<{
    tier: AdminRoleAuthGroup;
    userName: string;
    /** The delegated role, for a delegated administrator only. */
    role?: string | null;
    /** That delegation's scopes, already joined for reading. */
    scope?: string | null;
    appName: string;
}>();
</script>

<template>
    <span v-if="tier === 'DELEGATED_ADMIN'">
        Are you sure you want to stop
        <strong>{{ userName }}</strong> from granting
        <strong>{{ role }}</strong>
        <template v-if="scope">
            for <strong>{{ scope }}</strong>
        </template>
        in {{ appName }}? They will lose that immediately, and will keep any
        other roles they have been delegated.
    </span>

    <span v-else>
        Are you sure you want to remove <strong>{{ userName }}</strong> as an
        application administrator of {{ appName }}? They will immediately lose
        the ability to administer it, including appointing other administrators.
    </span>
</template>
