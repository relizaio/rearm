import { describe, it, expect } from 'vitest'
import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'

/**
 * The device support window panel is actually WIRED into the component page.
 *
 * This repo has no vue-tsc and `src/utils/` is unlinted, so a green build proves nothing about
 * a `<script setup>` identifier or a template binding -- a deleted block, a renamed ref or a
 * mistyped handler all build clean and fail only in a browser. That is exactly how #339 shipped
 * a blank tab. These assertions are narrow on purpose: they pin the things whose absence makes
 * the feature invisible while every unit test passes.
 */
const source = readFileSync(
    fileURLToPath(new URL('./ComponentView.vue', import.meta.url)), 'utf8')

describe('the device window panel is wired into the component page', () => {
    it('binds both date pickers to the window state', () => {
        expect(source).toMatch(/v-model:formatted-value="deviceWindow\.eos"/)
        expect(source).toMatch(/v-model:formatted-value="deviceWindow\.eol"/)
    })

    it('gates Save on an actual change', () => {
        expect(source).toMatch(/:disabled="!deviceWindowDirty"/)
        expect(source).toMatch(/@click="saveDeviceWindow"/)
    })

    /**
     * Hidden entirely on a backend that cannot answer for the field -- a CE mirror before the
     * deferred sync. Offering an editor whose save the server would reject is worse than not
     * offering it, and an always-empty panel would read as "this device declares nothing".
     */
    it('hides itself when the backend does not support the field', () => {
        expect(source).toMatch(/v-if="deviceWindowSupported && isDeviceComponent"/)
    })

    /** Only a device declares one; the server rejects the write otherwise. */
    it('hides itself on a component that is not a device', () => {
        expect(source).toMatch(/deviceClass !== 'NONE'/)
    })

    it('loads the window after the component data exists', () => {
        const mounted = source.slice(source.indexOf('onMounted(async () => {'))
        expect(mounted.indexOf('await initLoad()')).toBeLessThan(mounted.indexOf('await loadDeviceWindow()'))
    })

    /**
     * The window must NOT be folded into COMPONENT_FULL_DATA: that fragment is the
     * updateComponent mutation's response selection, and CE declares medicalProfile without
     * this subfield, so folding it in would invalidate the whole mutation and break EVERY
     * component save on CE. Same trap supportInjection hit in #339.
     */
    it('reads the window through its own document, not the mutation response', () => {
        const queries = readFileSync(
            fileURLToPath(new URL('../utils/graphqlQueries.ts', import.meta.url)), 'utf8')
        const fragment = queries.slice(queries.indexOf('const COMPONENT_FULL_DATA'),
            queries.indexOf('const COMPONENT_MUTATE'))
        expect(fragment).not.toMatch(/deviceSupportWindow/)
        expect(source).toMatch(/loadComponentDeviceWindow\(/)
    })

    /** The write goes through the shared builder, which owns the clear-vs-empty rule. */
    it('builds its mutation input through deviceWindowMutationInput', () => {
        expect(source).toMatch(/deviceWindowMutationInput\(componentUuid, deviceWindow, deviceWindowBaseline\)/)
    })
})

const releaseView = readFileSync(
    fileURLToPath(new URL('./ReleaseView.vue', import.meta.url)), 'utf8')

/**
 * The release page shows TWO DIFFERENT FACTS and never merges them (D7).
 *
 * Until 2026-09-10 one control edited `release.eos/eol` under the heading "Device support
 * window", which is the conflation the whole ruling removes: the device's commitment is a
 * labeling claim about hardware, the release's dates are TEA/CLE metadata about a version.
 */
describe('the release page separates the device window from release lifecycle', () => {
    it('renders the inherited device window read-only', () => {
        expect(releaseView).toMatch(/\{\{ inheritedDeviceWindow\.eos \|\| 'not declared' \}\}/)
        expect(releaseView).toMatch(/\{\{ inheritedDeviceWindow\.eol \|\| 'not declared' \}\}/)
    })

    /** No editor on the inherited value -- it is not this page's to change. */
    it('does not bind the inherited window to an input', () => {
        expect(releaseView).not.toMatch(/v-model[^\n]*inheritedDeviceWindow/)
    })

    /** A reader must be able to reach the place it IS editable. */
    it('links to the product component that declares it', () => {
        const section = releaseView.slice(releaseView.indexOf('<h3>Device support window</h3>'))
        expect(section.slice(0, 2000)).toMatch(/router-link/)
        expect(section.slice(0, 2000)).toMatch(/name: 'ComponentView'/)
    })

    /** The release's own dates stay editable, under a heading that does not claim otherwise. */
    it('keeps the release lifecycle dates editable and separately labelled', () => {
        expect(releaseView).toMatch(/<h3>Release lifecycle dates<\/h3>/)
        expect(releaseView).toMatch(/v-model:formatted-value="deviceWindow\.eos"/)
        expect(releaseView).toMatch(/This is not the device's support/)
    })

    /** Loaded from the component, not from the release's own fields. */
    it('sources the inherited window from the product component', () => {
        expect(releaseView).toMatch(/loadComponentDeviceWindow\(graphqlClient as any, componentUuid/)
    })
})
