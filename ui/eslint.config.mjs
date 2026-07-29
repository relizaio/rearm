// ESLint flat config (ESLint 9+ default, and the ONLY format ESLint 10 supports).
//
// Replaces .eslintrc.js. ESLint 10 also removed the `--ext` and `--ignore-path`
// CLI flags, so the `lint` script drops them: file extensions now come from the
// config blocks below, and ignores from the `ignores` entry rather than
// .gitignore. Until this migration `npm run lint` failed outright on flag
// parsing, so nothing was being linted at all.
//
// This is a like-for-like port of the old rule set, not a tightening: the same
// rules stay on, the same ones stay off. Two entries could not be carried over
// verbatim:
//   - `import/no-named-as-default` — eslint-plugin-import is not (and was not)
//     a dependency, so this rule never resolved and was already inert.
//   - `@typescript-eslint/semi` and `vue/no-setup-props-destructure` — both
//     removed upstream (formatting rules moved to @stylistic; the Vue rule was
//     renamed). Both were set to 'off', so dropping them changes nothing; the
//     Vue one is carried across under its successor name to preserve intent.
//
// `env: { node: true }` is not ported: it only mattered for `no-undef`, which
// vue3-essential does not enable (this config never extended eslint:recommended).
import pluginVue from 'eslint-plugin-vue'
import { defineConfigWithVueTs, vueTsConfigs } from '@vue/eslint-config-typescript'

export default defineConfigWithVueTs(
    {
        // Previously supplied via `--ignore-path .gitignore`.
        ignores: ['dist/**', 'node_modules/**', 'tests/e2e/reports/**'],
    },
    // eslint-plugin-vue 10 renamed the flat presets: Vue 3 is unprefixed
    // ('flat/essential'), Vue 2 carries the prefix ('flat/vue2-essential').
    // This is the same rule set as the old 'plugin:vue/vue3-essential'.
    pluginVue.configs['flat/essential'],
    // Parser wiring only — vueTsConfigs.base enables zero rules, so TypeScript
    // files and <script lang="ts"> blocks parse without importing an opinionated
    // rule set that was never active here.
    vueTsConfigs.base,
    {
        rules: {
            'no-console': 'off',
            'no-debugger': process.env.NODE_ENV === 'production' ? 'error' : 'off',
            indent: ['error', 4],
            semi: 'off',
            'vue/no-v-model-argument': 'off',
            // Successor to the removed vue/no-setup-props-destructure.
            'vue/no-setup-props-reactivity-loss': 'off',
        },
    }
)
