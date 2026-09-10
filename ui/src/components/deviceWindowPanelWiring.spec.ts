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
        // The gate reads deviceClassBaseline -- the class the SERVER last confirmed -- not
        // componentData, which initLoad() rehydrates from the store cache and which therefore
        // still held the old class right after a successful save.
        expect(source).toMatch(/deviceClassBaseline\.value !== 'NONE'/)
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
        // COMMENTS STRIPPED before the check. The claim is about the SELECTION SET -- what
        // the server is asked for -- and a `#` comment explaining why the field is absent is
        // the most natural place for the word to appear. Matching against comment prose made
        // this fail on a change that documented the rule it enforces, which is the same
        // mistake SupportEnumsSchemaEnumSyncTest made reading docstrings as enum values.
        const fragment = queries.slice(queries.indexOf('const COMPONENT_FULL_DATA'),
            queries.indexOf('const COMPONENT_MUTATE'))
            .split('\n').filter(l => !l.trim().startsWith('#')).join('\n')
        expect(fragment).not.toMatch(/deviceSupportWindow/)
        // deviceClass, by contrast, MUST be there: CE declares it, and the panel's gate reads
        // it off componentData. It was absent, so isDeviceComponent was false for every
        // component and the panel never rendered for anyone.
        expect(fragment).toMatch(/^\s*deviceClass\s*$/m)
        expect(source).toMatch(/loadComponentDeviceWindow\(/)
    })

    /** The write goes through the shared builder, which owns the clear-vs-empty rule. */
    it('builds its mutation input through deviceWindowMutationInput', () => {
        expect(source).toMatch(/deviceWindowMutationInput\(componentUuid,/)
        // name is String! on UpdateComponentInput -- a partial without it is rejected at
        // coercion, so the panel must pass one.
        // .value on BOTH: updatedComponent is a Ref, and `updatedComponent.name` in
        // <script setup> is undefined rather than the name -- the template unwraps Refs, the
        // script does not. The fallback silently sent name: undefined, which fails coercion.
        expect(source).toMatch(/componentData\.value\?\.name \|\| updatedComponent\.value\?\.name/)
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
    /**
     * A link to a route that does not exist is worse than no link: it renders, it is clickable,
     * and it goes nowhere. The first version of this test asserted `name: 'ComponentView'` --
     * which is the options-API COMPONENT name, not a route -- so it was green on a dead link.
     * It now pins a route name that router.ts actually declares.
     */
    it('links to the product component through a route that exists', () => {
        const section = releaseView.slice(releaseView.indexOf('<h3>Device support window</h3>'))
        expect(section.slice(0, 2500)).toMatch(/router-link/)
        expect(section.slice(0, 2500)).toMatch(/name: 'ProductsOfOrg'/)

        const router = readFileSync(
            fileURLToPath(new URL('../router.ts', import.meta.url)), 'utf8')
        expect(router).toMatch(/name: 'ProductsOfOrg'/)
    })

    /** The release's own dates stay editable, under a heading that does not claim otherwise. */
    it('keeps the release lifecycle dates editable and separately labelled', () => {
        expect(releaseView).toMatch(/<h3>Release lifecycle dates<\/h3>/)
        expect(releaseView).toMatch(/v-model:formatted-value="deviceWindow\.eos"/)
        // The copy says what these dates are FOR, not merely what they are not: "not the
        // device commitment" alone reads as "ignore these".
        expect(releaseView).toMatch(/END_OF_SUPPORT/)
        expect(releaseView).toMatch(/CLE lifecycle event for this release/)
        expect(releaseView).toMatch(/Separate from the <strong>device support window<\/strong>/)
    })

    /** Loaded from the component, not from the release's own fields. */
    it('sources the inherited window from the product component', () => {
        expect(releaseView).toMatch(/loadComponentDeviceWindow\(graphqlClient as any, componentUuid/)
    })
})

const distribution = readFileSync(
    fileURLToPath(new URL('./DistributionOfOrg.vue', import.meta.url)), 'utf8')

/**
 * The batch override (D7): Hardware and SaMD only, never plain software.
 *
 * Support commonly runs from sale or shipment ("seven years from date of sale"), which is a
 * fact about a BATCH -- and the ship date it anchors to is on this same form. Plain software
 * is not a device and has no section 524B commitment to override.
 */
describe('the shipment device-window override', () => {
    it('never renders for a plain-software shipment', () => {
        const block = distribution.slice(distribution.indexOf("THE BATCH'S DEVICE SUPPORT WINDOW OVERRIDE"))
        // The gate is the <template> immediately after the comment block.
        expect(block.slice(0, 900)).toMatch(/<template v-if="!isSoftwareShipment">/)
        // ...and the fields themselves live inside it, not outside.
        expect(block.indexOf('<template v-if="!isSoftwareShipment">'))
            .toBeLessThan(block.indexOf('shipForm.deviceWindowEos'))
    })

    it('binds both override dates', () => {
        expect(distribution).toMatch(/v-model:formatted-value="shipForm\.deviceWindowEos"/)
        expect(distribution).toMatch(/v-model:formatted-value="shipForm\.deviceWindowEol"/)
    })

    /** The in-force line has to name WHERE the window came from, or an operator cannot tell
     *  an inherited value from one this batch already overrode. */
    it('names the level the effective window came from', () => {
        expect(distribution).toMatch(/w\.source === 'SHIPMENT' \? 'this batch' : 'the product component'/)
    })

    /**
     * Seeded from the batch's OWN window, never the effective one: seeding from the effective
     * value would turn an inherited window into an override the moment anything else was saved.
     */
    it('seeds the editor from the batch override, not the effective window', () => {
        expect(distribution).toMatch(/shipForm\.deviceWindowEos = existing\.deviceSupportWindow\?\.eos/)
    })

    /** Blank-both after something was declared is a retraction and must send the flag. */
    it('sends the clear flag rather than an empty window object', () => {
        const save = distribution.slice(distribution.indexOf('D7, same three rules as the component panel'))
        expect(save.slice(0, 1200)).toMatch(/input\.clearDeviceSupportWindow = true/)
        expect(save.slice(0, 1200)).not.toMatch(/deviceSupportWindow = \{ eos: null, eol: null \}/)
    })

    /** SaaS-only surface, so the document is simply never issued by a CE build. */
    it('asks for the window in the shipments query', () => {
        expect(distribution).toMatch(/effectiveDeviceSupportWindow \{ eos eol source \}/)
    })
})

/**
 * The four defects Layer 1 found on this branch, each pinned by the property that was
 * violated rather than by the text that fixed it.
 *
 * All four passed every existing gate: the UI has no vue-tsc, `src/utils/` is unlinted, and
 * a green build plus a green suite cannot see an unregistered component, an unselected
 * GraphQL field or a query that is never issued. They shipped a panel that could not render,
 * a read that always returned early, and a CE protection that was documented but absent.
 */
describe('the D7 UI surfaces can actually render and read', () => {
    it('ComponentView imports NDatePicker, which its pickers need to exist', () => {
        // naive-ui is NOT globally registered in main.ts -- every component is imported per
        // file. ReleaseView.vue imports NDatePicker for exactly this reason. Without it the
        // two <n-date-picker> elements resolve to nothing and the panel has no date inputs.
        const imports = source.slice(source.indexOf("from 'naive-ui'") - 900,
            source.indexOf("from 'naive-ui'"))
        expect(imports).toMatch(/\bNDatePicker\b/)
        expect(source).toMatch(/<n-date-picker/)
    })

    it('ComponentView can set the device class, not only read it', () => {
        // Before this, deviceClass could only be set at CREATE time, so the window panel --
        // which correctly renders only for a device -- was unreachable for every component
        // that already existed, and D7's "declare it on the product component" had no path.
        expect(source).toMatch(/saveDeviceClass/)
        expect(source).toMatch(/DEVICE_CLASS_OPTIONS/)
        // Re-read after the write: NONE retracts the window server-side, and leaving stale
        // dates in the form would show a section 524B commitment the server no longer holds.
        const fn = source.slice(source.indexOf('async function saveDeviceClass'))
            .slice(0, 1400)
        expect(fn).toMatch(/loadDeviceWindow\(\)/)
    })

    it('the release page reads the component uuid from a field its query selects', () => {
        const release = readFileSync(
            fileURLToPath(new URL('./ReleaseView.vue', import.meta.url)), 'utf8')
        // SINGLE_RELEASE_PRODUCT_GQL -- the query used for PRODUCT releases, which is the
        // only surface this panel renders on -- does not select the flat `component` field.
        // Reading it there returned undefined, so the inherited window said "not declared"
        // regardless of what was declared.
        const fn = release.slice(release.indexOf('async function loadInheritedDeviceWindow'))
            .slice(0, 900)
        expect(fn).toMatch(/componentDetails\?\.uuid/)
        const queries = readFileSync(
            fileURLToPath(new URL('../utils/graphqlQueries.ts', import.meta.url)), 'utf8')
        const product = queries.slice(queries.indexOf('const SINGLE_RELEASE_PRODUCT_GQL'))
            .slice(0, 6000)
        expect(product).toMatch(/componentDetails/)
    })

    it('the addendum collector falls back to CORE instead of issuing FULL alone', () => {
        const addendum = readFileSync(
            fileURLToPath(new URL('../utils/addendumData.ts', import.meta.url)), 'utf8')
        const fn = addendum.slice(addendum.indexOf('export async function collectAddendumData'))
            .slice(0, 2000)
        // Issuing FULL directly made ADDENDUM_RELEASE_QUERY_CORE dead outside its own spec,
        // so the CE protection the split exists for was documented but not present: on CE the
        // addendum would not generate at all, because the missing subfield invalidates the
        // WHOLE document rather than returning null.
        expect(fn).toMatch(/loadWithSchemaDriftFallback/)
        expect(fn).toMatch(/ADDENDUM_RELEASE_QUERY_CORE/)
    })

    it('the statement font check covers the user-entered provenance strings', () => {
        const stmt = readFileSync(
            fileURLToPath(new URL('../utils/deviceSupportStatement.ts', import.meta.url)), 'utf8')
        const fn = stmt.slice(stmt.indexOf('function statementStrings')).slice(0, 1600)
        // A site name and a lot code are the only strings in the document a HUMAN types, and
        // therefore the likeliest to carry a glyph the embedded font cannot draw. Omitting
        // them let one through the refusal and into a patient-facing PDF as a blank box.
        for (const f of ['siteName', 'shipDate', 'batchIdentifier']) expect(fn).toMatch(f)
    })

    it('the panels are siblings of coreSettingsActions, not children of it', () => {
        // coreSettingsActions has v-if="hasCoreSettingsChanges && isWritable" -- it is the
        // UNSAVED-CHANGES action bar. Nested inside it, the device window panel appeared only
        // after you had edited some other core setting and vanished the moment you saved, so
        // on a freshly opened component -- every reader following the walkthrough -- it did
        // not exist at all. This is the defect that made the whole D7 write path unreachable,
        // and neither the build, the suite nor a GraphQL-driven probe could see it.
        const bar = source.indexOf('class="coreSettingsActions"')
        const panel = source.indexOf('THE DEVICE SUPPORT WINDOW LIVES HERE NOW')
        expect(bar).toBeGreaterThan(-1)
        expect(panel).toBeGreaterThan(bar)
        // Everything between the action bar and the panel, with the panel OUTSIDE: the bar's
        // own div must have closed first. Count div depth across the gap.
        const gap = source.slice(bar, panel)
        const opens = (gap.match(/<div\b/g) || []).length
        const closes = (gap.match(/<\/div>/g) || []).length
        expect(closes).toBeGreaterThanOrEqual(opens)
    })
})
