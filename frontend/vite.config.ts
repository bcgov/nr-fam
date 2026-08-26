/// <reference types="vitest" />

import { defineConfig, loadEnv } from "vite";
import react from "@vitejs/plugin-react";
import { fileURLToPath, URL } from "url";
import path from "path";

export default defineConfig(({ mode }) => {
    const env = loadEnv(mode, process.cwd(), "");
    const port = parseInt(env.VITE_PORT || "3000");

    return {
        plugins: [react()],
        test: {
            globals: true,
            environment: "jsdom",
            setupFiles: ["src/test/setup.ts"],
            /*
                The Playwright suite also uses `.spec.ts`, and vitest would
                otherwise collect e2e/ and report every file as "0 test" -
                green, but only because it found nothing to run. Playwright
                owns that directory; vitest owns src/.
            */
            exclude: ["**/node_modules/**", "**/dist/**", "e2e/**"],
            coverage: {
                reporter: ["text", "lcov"],
            },
        },
        build: {
            /*
                esbuild, not Vite 8's default lightningcss.

                Carbon's stylesheet contains two things lightningcss refuses:
                `@position-try` from the *next* date picker, and a top-level
                `> .cds--text-input` in the time picker. Both fail the whole
                build with a message pointing into generated CSS that names
                neither the rule nor the component, and stripping them one at a
                time is an open-ended fight with a stricter parser.

                nr-fsp-new never hit this - Vite 6 minified with esbuild, which
                passes both through. Vite 8 no longer bundles esbuild, so it is
                an explicit devDependency here.
            */
            cssMinify: "esbuild",
            chunkSizeWarningLimit: 1600,
        },
        resolve: {
            alias: {
                "@": fileURLToPath(new URL("./src", import.meta.url)),
                "~bootstrap": path.resolve(__dirname, "node_modules/bootstrap"),
            },
            extensions: [".js", ".ts", ".jsx", ".tsx"],
        },
        server: {
            port: port,
            // Mirrors the Caddy config the deployed frontend runs behind: /api is
            // proxied to the backend and the prefix is stripped, so the API is
            // same-origin in development too. Without this, local dev would need
            // CORS and an absolute base URL that production does not use.
            proxy: {
                "/api": {
                    target: env.VITE_BACKEND_URL || "http://localhost:8080",
                    changeOrigin: true,
                    rewrite: (path) => path.replace(/^\/api/, ""),
                },
            },
        },
        css: {
            preprocessorOptions: {
                scss: {
                    // Carbon's Sass imports its own packages by bare specifier.
                    loadPaths: [path.resolve(__dirname, "node_modules")],
                    api: "modern-compiler",
                    silenceDeprecations: [
                        "mixed-decls",
                        "global-builtin",
                        "import",
                    ],
                },
            },
        },
    };
});
