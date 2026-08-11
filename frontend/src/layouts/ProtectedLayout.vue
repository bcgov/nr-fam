<script setup lang="ts">
import Header from "@/layouts/Header.vue";
import SideNav from "@/layouts/SideNav.vue";

/**
 * The shell for signed-in pages.
 *
 * This used to gate rendering on a terms-and-conditions check: a Business BCeID
 * delegated admin had to accept FAM's terms before continuing. Delegated
 * administration and the acceptance record both went to CSS in V94, so there is
 * nothing left to gate on and the layout renders straight away.
 */
</script>

<template>
    <div id="protected-layout-container">
        <Header title="FAM" subtitle="Forests Access Management" />
        <SideNav />
        <main class="main">
            <router-view />
        </main>
    </div>
</template>
<style lang="scss">
#protected-layout-container {
    .terms-and-condition-spinner-container {
        .spinner-container {
            display: flex;
            width: 100vw;
            height: 85vh;
            justify-content: center;
            align-items: center;
            margin-left: -18.5rem; // counter the sidenav offset
        }
    }
}
</style>
