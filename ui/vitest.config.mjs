import { defineConfig } from 'vitest/config'
import { fileURLToPath, URL } from 'url'
import vue from '@vitejs/plugin-vue'

// Standalone vitest config kept separate from vite.config.mjs so the app
// build (vite build) never pulls in test-only settings. Node environment by
// default: most specs are pure-logic + schema-validation tests, no DOM. A
// component spec opts into a DOM with `// @vitest-environment happy-dom` and
// mounts through @vue/test-utils, which is what the vue plugin is here for.
export default defineConfig({
    plugins: [vue()],
    resolve: {
        alias: {
            '@': fileURLToPath(new URL('./src', import.meta.url)),
        },
    },
    test: {
        environment: 'node',
        include: ['src/**/*.spec.ts', 'test/**/*.spec.ts'],
    },
})
