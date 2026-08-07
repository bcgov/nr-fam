/// <reference types="vitest" />

import { defineConfig, loadEnv } from "vite";
import vue from "@vitejs/plugin-vue";
import Components from "unplugin-vue-components/vite";
import { BootstrapVueNextResolver } from "unplugin-vue-components/resolvers";
import { fileURLToPath, URL } from "url";
import path from "path";

export default defineConfig(({ mode }) => {
    const env = loadEnv(mode, process.cwd(), "");
    const port = parseInt(env.VITE_PORT || "5173");

    return {
        plugins: [
            vue(),
            Components({
                resolvers: [BootstrapVueNextResolver()],
            }),
        ],
        test: {
            globals: true,
            environment: "jsdom",
            coverage: {
                reporter: ["text", "lcov"],
            },
        },
        build: {
            chunkSizeWarningLimit: 1600,
        },
        resolve: {
            alias: {
                "@": fileURLToPath(new URL("./src", import.meta.url)),
                "~bootstrap": path.resolve(__dirname, "node_modules/bootstrap"),
                vue: "vue/dist/vue.esm-bundler.js",
            },
            extensions: [".js", ".ts", ".jsx", ".tsx", ".vue"],
        },
        server: {
            port: port,
            // Mirrors the Caddy config the deployed frontend runs behind: /api is
            // proxied to the backend and the prefix is stripped, so the API is
            // same-origin in development too. Without this, local dev would need
            // CORS and an absolute base URL that production does not use.
            proxy: {
                "/api": {
                    target: env.VITE_BACKEND_URL || "http://localhost:3000",
                    changeOrigin: true,
                    rewrite: (path) => path.replace(/^\/api/, ""),
                },
            },
        },
        css: {
            preprocessorOptions: {
                scss: {
                    additionalData: `
            @use '@bcgov-nr/nr-theme/design-tokens/colors.scss' as colors;
            @use '@carbon/type' as type;
          `,
                },
            },
        },
    };
});
