<script setup lang="ts">
import ProtectedLayout from "@/layouts/ProtectedLayout.vue";
import AuthProvider from "@/providers/AuthProvider.vue";
import { VueQueryDevtools } from "@tanstack/vue-query-devtools";
import DynamicDialog from "primevue/dynamicdialog";
import Toast from "primevue/toast";
import { computed } from "vue";
import { useRoute } from "vue-router";

// Get the current route
const route = useRoute();

// Use computed to determine which layout to use
const layoutComponent = computed(() => {
    return route.meta.layout === "ProtectedLayout" ? ProtectedLayout : null;
});
</script>

<template>
    <AuthProvider>
        <!-- Render the layout if provided, otherwise just the router-view -->
        <component v-if="layoutComponent" :is="layoutComponent" />
        <!-- No layout, just render the view -->
        <router-view v-else />
        <VueQueryDevtools />
        <DynamicDialog />
        <!--
            Mounted once, above the router view, so a toast raised just before a
            redirect survives it. Granting navigates back to Manage permissions
            the moment it succeeds; a Toast inside either screen would be
            unmounted mid-flight and the confirmation would never be seen.
        -->
        <Toast position="top-right" />
    </AuthProvider>
</template>

<style lang="scss">
@use "@/assets/styles/styles";
@use "@/passthrough/dialog/dialogPassThrough.scss";
@use "@/passthrough/input/inputPassThrough.scss";
@use "@/passthrough/button/buttonPassThrough.scss";
@use "@/passthrough/radiobutton/radioButtonPassThrough.scss";
</style>
