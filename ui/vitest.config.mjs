import { defineConfig } from 'vitest/config'
import { fileURLToPath, URL } from 'url'

// Standalone vitest config kept separate from vite.config.mjs so the app
// build (vite build) never pulls in test-only settings. Node environment:
// these are pure-logic + schema-validation tests, no DOM.
export default defineConfig({
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
