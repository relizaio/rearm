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
/** The Medical device tab's status block, out of ComponentView.vue since FDAW-78 (row 16). */
const statusSource = readFileSync(
    fileURLToPath(new URL('./MedicalDeviceSetupStatus.vue', import.meta.url)), 'utf8')

/** A top-level function of the page, from its opening to the first `}` at column 0. */
function fnBody (opening: string): string {
    const at = source.indexOf(opening)
    expect(at, opening).toBeGreaterThan(-1)
    const end = source.slice(at).search(/\n\}\)?\n/)
    return end < 0 ? '' : source.slice(at, at + end + 3)
}

describe('the device window panel is wired into the component page', () => {
    it('binds both date pickers to the window state', () => {
        expect(source).toMatch(/v-model:formatted-value="deviceWindow\.eos"/)
        expect(source).toMatch(/v-model:formatted-value="deviceWindow\.eol"/)
    })

    /**
     * FDAW-3, B8: ONE Save at the bottom of the Device tab, like Core Settings, shown only when the
     * tab holds a draft. The two section buttons are gone.
     */
    it('has one Save bar for the whole Device tab, gated on an actual change', () => {
        const at = source.indexOf('data-testid="component-device-save"')
        expect(at).toBeGreaterThan(-1)
        const bar = source.slice(source.lastIndexOf('<div class="deviceSettingsActions"', at), at)
        // FDAW-18 (F-1): the bar also shows for a question-2 edit and for the caller's pending setup
        expect(bar).toMatch(/v-if="\(hasDeviceChanges \|\| hasFdaChanges \|\| canCompleteSetup\) && isWritable"/)
        expect(source.slice(at, source.indexOf('>', at))).toMatch(/@click="saveDevice"/)
        const complete = source.indexOf('data-testid="device-complete-setup"')
        expect(complete).toBeGreaterThan(at)
        expect(source.slice(complete, source.indexOf('>', complete))).toMatch(/@click="saveDevice"/)
        expect(source).toMatch(/@click="resetDevice"/)
        for (const gone of ['saveDeviceClass', 'saveDeviceWindow', 'Save window', 'Save device class',
            'submitDeviceClass', 'submitDeviceWindow']) {
            expect(source, gone).not.toContain(gone)
        }
        expect(source).toMatch(/deviceClassEdit\.value !== deviceClassBaseline\.value \|\| deviceWindowDirty\.value/)
    })

    /** FDAW-3, B11: the pickers are named by their IMDRF N70 meaning, from the shared labels. */
    it('labels the pickers from deviceDateLabels', () => {
        expect(source).toMatch(/import \{ EOS_FIELD_LABEL, EOL_FIELD_LABEL \} from '@\/utils\/deviceDateLabels'/)
        expect(source).toContain('{{ EOS_FIELD_LABEL }}')
        expect(source).toContain('{{ EOL_FIELD_LABEL }}')
        expect(source).not.toContain('End of life / end of sale (EOL)')
        expect(source).not.toContain('End of support (EOS)')
    })

    /** FDAW-3, B3: the order of the two dates is a warning shown with the window, never a refusal. */
    it('shows the window warnings under the pickers', () => {
        const at = source.indexOf('data-testid="component-device-window-warning"')
        expect(at).toBeGreaterThan(-1)
        const tag = source.slice(source.lastIndexOf('<n-alert', at), source.indexOf('</n-alert>', at))
        expect(tag).toMatch(/v-if="deviceWindowWarnings\.length"/)
        expect(tag).toMatch(/type="warning"/)
        expect(tag).toMatch(/v-for="w in deviceWindowWarnings"/)
        expect(tag).toMatch(/\{\{ w\.message \}\}/)
        expect(source).toMatch(/deviceWindowWarnings\.value = r\.warnings/)
    })

    /**
     * FDAW-3, B8: the false "Unsaved Changes" after a Device save. The save re-reads the form, so
     * the close guard's snapshot must be taken again, as the core save does.
     */
    it('refreshes the close guard snapshot after a Device save', () => {
        const refresh = fnBody('async function refreshAfterDeviceSave')
        expect(refresh).toContain('originalComponent.value = commonFunctions.deepCopy(updatedComponent.value)')
        expect(refresh.indexOf('await initLoad()')).toBeGreaterThan(-1)
        expect(refresh.indexOf('originalComponent.value')).toBeGreaterThan(refresh.indexOf('await loadDeviceWindow()'))
        const submit = fnBody('async function submitDevice ')
        expect(submit.indexOf('await refreshAfterDeviceSave()')).toBeGreaterThan(submit.indexOf('revisionSaved('))
        expect(submit).toMatch(/notify\('warning', 'Saved with a warning'/)
        expect(fnBody('async function reloadComponentForm')).toContain('await refreshAfterDeviceSave()')
    })

    /** FDAW-3, F-4: an unsaved Device draft is prompted for on close, and discarded only on "Yes". */
    it('covers the Device draft in the close guard, not in the tab switch', () => {
        const close = fnBody('const handleComponentSettingsClose')
        expect(close).toMatch(/hasDeviceChanges/)
        expect(close.indexOf('resetDevice()')).toBeGreaterThan(close.indexOf('confirmUnsavedChanges()'))
        expect(fnBody('async function handleTabSwitch')).not.toMatch(/hasDeviceChanges/)
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
        // FDAW-3, B8: the gate reads the DRAFT class, so picking a class reveals the pickers and
        // the class and its dates go in one Save (the server applies the class first).
        expect(source).toMatch(/!!deviceClassEdit\.value && deviceClassEdit\.value !== 'NONE'/)
        expect(source).not.toMatch(/deviceClassBaseline\.value !== 'NONE'/)
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
    it('builds its mutation input through deviceSaveInput', () => {
        // deviceSaveInput delegates the window part to deviceWindowMutationInput
        // (utils/componentDeviceWindow.spec.ts runs it). FDAW-18: the updateComponent save is the
        // fallback where the setup is not served (CE); medicalDeviceSetupInput reuses the same rule.
        expect(fnBody('async function submitDeviceComponentOnly ')).toMatch(/deviceSaveInput\(componentUuid,/)
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
 * The release page does not show the device window AT ALL, and shows the release's own
 * lifecycle dates as metadata.
 *
 * TWO REVISIONS OF THE SAME RULING. Until 2026-09-10 one control edited `release.eos/eol`
 * under the heading "Device support window" -- the conflation D7 removes. The fix split them
 * into two adjacent panels on the Components tab, and the operator walkthrough found that two
 * headings a few pixels apart were still read as one control, often enough that the explainer
 * had to open by saying they were not the same thing.
 *
 * So on 2026-09-21 the read-only device window was REMOVED from the release page rather than
 * relabelled. It is declared on the product component and resolved per shipment on
 * Distribution -- both surfaces are asserted above and below -- and a third read-only copy on
 * a page that cannot edit it bought nothing but the confusion. The release's own dates moved
 * to the Meta tab, where the rest of this release's metadata lives.
 *
 * These assertions therefore pin an ABSENCE. That is deliberate: the failure mode is someone
 * re-adding a device window to the release page, and a spec that only checked the Meta tab
 * would not notice.
 */
describe('the release page carries release lifecycle dates and no device window', () => {
    it('does not render a device support window', () => {
        expect(releaseView).not.toContain('Device support window</h3>')
        expect(releaseView).not.toContain('inheritedDeviceWindow')
        expect(releaseView).not.toContain('loadComponentDeviceWindow')
    })

    /** The helper it used is still live for the surfaces that DO own the window. */
    it('leaves the component-window loader in place for the surfaces that own it', () => {
        const component = readFileSync(
            fileURLToPath(new URL('./ComponentView.vue', import.meta.url)), 'utf8')
        expect(component).toMatch(/loadComponentDeviceWindow\(/)
    })

    /** The release's own dates stay editable, under a heading that does not claim otherwise. */
    it('keeps the release lifecycle dates editable and separately labelled', () => {
        // The heading text, not the spacing class it happens to carry: a margin tweak is not
        // a regression and must not red the suite.
        expect(releaseView).toMatch(/<h3[^>]*>Release lifecycle dates<\/h3>/)
        expect(releaseView).toMatch(/v-model:formatted-value="deviceWindow\.eos"/)
        // The copy says what these dates are FOR, not merely what they are not: "not the
        // device commitment" alone reads as "ignore these".
        expect(releaseView).toMatch(/END_OF_SUPPORT/)
        expect(releaseView).toMatch(/CLE lifecycle event for this release/)
        expect(releaseView).toMatch(/Separate from the <strong>device support window<\/strong>/)
        // It no longer says "above": there is nothing above it to point at, and a sentence
        // that directs a reader to a panel that is not there is worse than no sentence.
        expect(releaseView).not.toMatch(/device support window<\/strong> above/)
        // It still says WHERE the device window lives, which is the half worth keeping.
        expect(releaseView).toMatch(/declared on the product component, not here/)
    })

    /** In the Meta tab, with the rest of this release's metadata -- not on Components. */
    it('renders the lifecycle dates inside the Meta tab', () => {
        // lastIndexOf: the BOM Components pane nests its own <n-tabs>, so the first
        // closing tag in the file belongs to that inner set and slicing to it yields ''.
        // An empty slice makes every toContain below fail loudly, which is how this was
        // caught -- but a `not.toContain` in the same position would have passed silently.
        const meta = releaseView.slice(
            releaseView.indexOf('<n-tab-pane name="meta" tab="Meta">'),
            releaseView.lastIndexOf('</n-tabs>'))
        expect(meta).toContain('Release lifecycle dates')
        expect(meta).toContain('v-model:formatted-value="deviceWindow.eos"')
        expect(meta.indexOf('<h3>Notes</h3>'))
            .toBeLessThan(meta.indexOf('Release lifecycle dates'))

        const ca = releaseView.indexOf('<n-tab-pane name="components" tab="Components">')
        const cb = releaseView.indexOf('name="underlyingArtifacts"')
        // Same non-empty guard as the Meta slice above, and for the sharper reason: every
        // assertion below is a not.toContain, which an empty slice satisfies in silence.
        expect(ca, 'no Components pane').toBeGreaterThan(-1)
        expect(cb, 'underlyingArtifacts does not follow Components').toBeGreaterThan(ca)
        const components = releaseView.slice(ca, cb)
        expect(components).not.toContain('Release lifecycle dates')
        expect(components).not.toContain('deviceWindow')
        // The assessment justification moved to the Support tab in the same pass.
        expect(components).not.toContain('releaseNarrative')
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

    /**
     * WIRING only. What the label SAYS is behaviour, and is tested as behaviour in
     * utils/shipmentDeviceWindow.spec.ts -- this used to pin the literal ternary, which meant
     * renaming the local `w` failed the spec with the behaviour unchanged, and a genuinely
     * wrong string would have passed as long as the shape matched.
     */
    it('renders the in-force line through the shared label builder', () => {
        expect(distribution).toMatch(/buildEffectiveWindowLabel\(/)
        expect(distribution).toMatch(/from '@\/utils\/componentDeviceWindow'/)
    })

    it('builds the shipment mutation through the shared rule, not a second copy of it', () => {
        // The clear-vs-empty rule is identical to the component panel's. Two copies in two
        // files are two chances to disagree about what "retract" means on a 524B commitment.
        expect(distribution).toMatch(/applyShipmentWindowToInput\(/)
        expect(distribution).not.toMatch(/input\.clearDeviceSupportWindow = true/)
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
        // The RULE moved to utils/componentDeviceWindow.ts and is tested as behaviour in
        // utils/shipmentDeviceWindow.spec.ts. What stays here is the wiring claim: this file
        // delegates rather than keeping a second copy of a rule about what "retract" means.
        expect(distribution).toMatch(/applyShipmentWindowToInput\(/)
        expect(distribution).not.toMatch(/input\.clearDeviceSupportWindow = true/)
    })

    /** SaaS-only surface, so the document is simply never issued by a CE build. */
    it('asks for the window in the shipments query', () => {
        expect(distribution).toMatch(/effectiveDeviceSupportWindow \{ eos eol source \}/)
    })

    /**
     * FDAW-3, B3: the override's derived warning is an additive field, so the list reads the full
     * document first and the core one on a drift refusal; the edit form names the warning beside
     * the in-force line, and a save that produced one says so.
     */
    it('reads the shipments through the drift fallback and renders the warning', () => {
        const load = distribution.slice(distribution.indexOf('async function loadShipments'),
            distribution.indexOf('async function loadDevices'))
        expect(load).toMatch(/loadWithSchemaDriftFallback\(/)
        expect(load).toMatch(/fullQuery: SHIPMENTS_FULL_QUERY, coreQuery: SHIPMENTS_CORE_QUERY/)
        const at = distribution.indexOf('data-testid="shipment-device-window-warning"')
        expect(at).toBeGreaterThan(distribution.indexOf('In force: {{ effectiveWindowLabel }}'))
        const tag = distribution.slice(distribution.lastIndexOf('<span', at), distribution.indexOf('</span>', at))
        expect(tag).toMatch(/v-for="w in editingShipmentWarnings"/)
        expect(tag).toMatch(/\{\{ w\.message \}\}/)
        expect(distribution).toMatch(/notify\('warning', 'Saved with a warning'/)
    })

    /**
     * FDAW-3, B3: after a save, the warning notify must read back the shipment it just saved
     * (by `savedUuid`), not some other row in the list -- `shipments.value` can hold many. A
     * wrong comparison here would show the warning for the wrong batch, or none at all, right
     * after a save that DID produce one.
     */
    it('reads the just-saved shipment back by its own uuid for the warning notify', () => {
        const start = distribution.indexOf('async function shipProduct')
        expect(start).toBeGreaterThan(-1)
        const end = distribution.indexOf('\n}\n', start)
        const fn = distribution.slice(start, end > -1 ? end + 3 : undefined)
        expect(fn).toMatch(/shipments\.value\.find\(\(x: any\) => x\.uuid === savedUuid\)/)
        expect(fn).not.toMatch(/x\.uuid !== savedUuid/)
    })

    /** FDAW-3, B11: the override pickers carry the IMDRF N70 names. */
    it('labels the override pickers from deviceDateLabels', () => {
        expect(distribution).toMatch(/import \{ EOS_FIELD_LABEL, EOL_FIELD_LABEL \} from '@\/utils\/deviceDateLabels'/)
        expect(distribution).toContain(':label="BATCH_EOS_LABEL"')
        expect(distribution).toContain(':label="BATCH_EOL_LABEL"')
        expect(distribution).not.toContain('end of life / end of sale')
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
        // FDAW-18: question 1 (Yes / No with the 821 checkbox) sets the class
        expect(source).toMatch(/data-testid="device-is-medical" :value="isDeviceComponent"\s+@update:value="setIsDevice"/)
        expect(source).toMatch(/data-testid="device-tracked-821"/)
        // Re-read after the write: NONE retracts the window server-side, and leaving stale
        // dates in the form would show a section 524B commitment the server no longer holds.
        expect(fnBody('async function refreshAfterDeviceSave')).toMatch(/await loadDeviceWindow\(\)/)
    })

    // The read this pinned is GONE: the release page no longer loads the inherited window, so
    // the "which field does the query select" hazard has no reader left on that page. What
    // survives is the half that still has one -- SINGLE_RELEASE_PRODUCT_GQL must select
    // componentDetails, which the Support tab's PRODUCT gate and the export modal both read.
    it('the product release query selects componentDetails for the surfaces that read it', () => {
        const release = readFileSync(
            fileURLToPath(new URL('./ReleaseView.vue', import.meta.url)), 'utf8')
        expect(release).not.toContain('loadInheritedDeviceWindow')
        expect(release).toMatch(/updatedRelease\.value\?\.componentDetails\?\.type === 'PRODUCT'/)
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

    it('the panels live in their own Device tab, outside Core Settings and its coreSettingsActions', () => {
        // coreSettingsActions has v-if="hasCoreSettingsChanges && isWritable" -- it is the
        // UNSAVED-CHANGES action bar. Nested inside it, the device window panel appeared only
        // after you had edited some other core setting and vanished the moment you saved, so
        // on a freshly opened component -- every reader following the walkthrough -- it did
        // not exist at all. This is the defect that made the whole D7 write path unreachable,
        // and neither the build, the suite nor a GraphQL-driven probe could see it. The panels
        // now sit in a Device tab of their own (one tab per Save), shown where the backend
        // answers for the window.
        const core = source.indexOf('<n-tab-pane name="Core Settings">')
        const bar = source.indexOf('class="coreSettingsActions"')
        const coreEnd = source.indexOf('</n-tab-pane>', core)
        expect(core).toBeGreaterThan(-1)
        expect(bar).toBeGreaterThan(core)
        expect(bar).toBeLessThan(coreEnd)
        const deviceLine = '<n-tab-pane name="device" tab="Medical device" v-if="deviceWindowSupported">'
        const device = source.indexOf(deviceLine)
        expect(device).toBeGreaterThan(coreEnd)
        const pane = source.slice(device, source.indexOf('</n-tab-pane>', device))
        expect(pane).toContain('THE DEVICE SUPPORT WINDOW LIVES HERE NOW')
        expect(pane).toContain('<div v-if="deviceWindowSupported" style="margin-top: 22px;">')
        expect(pane).toContain('<h4>{{ questionIsMedical(componentData?.name) }}</h4>')
        expect(pane).toContain('<div v-if="deviceWindowSupported && isDeviceComponent"')
        expect(pane).toContain('<h4>Device support window</h4>')
        expect(pane).not.toContain('class="coreSettingsActions"')
        // FDAW-3, B8: the Device tab's own Save bar is the LAST child of the pane
        const deviceBar = pane.indexOf('class="deviceSettingsActions"')
        expect(deviceBar).toBeGreaterThan(pane.indexOf('<h4>Device support window</h4>'))
        expect(pane.slice(deviceBar)).not.toMatch(/<h4>/)
        // Every div of the pane closes inside it.
        const opens = (pane.match(/<div\b/g) || []).length
        const closes = (pane.match(/<\/div>/g) || []).length
        expect(closes).toBe(opens)
        const corePane = source.slice(core, coreEnd)
        expect(corePane).not.toContain('Device class')
        expect(corePane).not.toContain('saveDeviceWindow')
    })
})

/**
 * FDAW-18 (B5), architecture 4 row 18: the Medical device tab's two questions and one Save, wired.
 */
describe('the Medical device tab is wired into the component page (FDAW-18)', () => {
    it('carries the questions, the defaults line, the status and Complete setup', () => {
        for (const id of ['device-is-medical', 'device-tracked-821', 'device-fda-premarket', 'device-fda-defaults',
            'device-fda-still-on', 'device-complete-setup']) {
            expect(source, id).toContain('data-testid="' + id + '"')
        }
        // FDAW-78 row 17: the status block moved to MedicalDeviceSetupStatus.vue with its three test ids
        expect(source).toContain('<MedicalDeviceSetupStatus')
        expect(source).not.toContain("'device-setup-line-'")
        expect(source).not.toContain("'device-step-not-done-'")
        expect(statusSource).toContain('data-testid="device-setup-status"')
        expect(statusSource).toContain(":data-testid=\"'device-step-not-done-' + l.step\"")
        expect(source).not.toContain('DEVICE_CLASS_OPTIONS')
        expect(source).not.toMatch(/label: 'MEDICAL_TRACKED'/)
        expect(source).not.toMatch(/label: 'MEDICAL_UNTRACKED'/)
    })

    it('closes on real edits only: the guard tests hasFdaChanges, never canCompleteSetup', () => {
        const close = fnBody('const handleComponentSettingsClose')
        expect(close).toMatch(/hasFdaChanges/)
        expect(close).not.toMatch(/canCompleteSetup/)
    })

    it('sends one setUpMedicalDevice and counts the row write only when the device step wrote it (F-4)', () => {
        const submit = fnBody('async function submitDevice ')
        expect(submit).toMatch(/mutation: SET_UP_MEDICAL_DEVICE/)
        expect(submit).toMatch(/withExpectedRevision\(medicalDeviceSetupInput\(/)
        const guard = submit.indexOf('if (deviceStepWritten(')
        expect(guard).toBeGreaterThan(-1)
        const saved = submit.indexOf('revisionSaved(')
        expect(saved).toBeGreaterThan(guard)
        expect(submit.indexOf('revisionSaved(', saved + 1)).toBe(-1)
        expect(submit.slice(guard, submit.indexOf('}', guard))).toContain('revisionSaved(expected)')
        expect(submit.indexOf('await refreshAfterDeviceSave()')).toBeGreaterThan(saved)
        expect(submit).toMatch(/setupStepNotes\.value = setupStepLines\(result\)/)
    })

    it('counts a UDI edit as a device change and re-reads the status after a save (F-6)', () => {
        expect(source).toMatch(/deviceClassEdit\.value !== deviceClassBaseline\.value \|\| deviceWindowDirty\.value \|\| deviceProfileDirty\.value/)
        expect(fnBody('async function refreshAfterDeviceSave')).toMatch(/await loadSetupStatus\(\)/)
        expect(fnBody('function resetDevice')).toMatch(/fdaPremarketEdit\.value = fdaPremarketBaselineValue\.value/)
    })

    it('reads the UDI fields with the window as core fields, and the form fragment still selects no window', () => {
        const dw = readFileSync(fileURLToPath(new URL('../utils/componentDeviceWindow.ts', import.meta.url)), 'utf8')
        const doc = dw.slice(dw.indexOf('export const COMPONENT_DEVICE_WINDOW_QUERY'), dw.indexOf('/** One derived observation'))
        for (const f of ['issuingAgency', 'udiBearing', 'gudidDefaults { brandName companyName gmdnCode }']) {
            const line = doc.split('\n').find(l => l.includes(f))
            expect(line, f).toBeDefined()
            expect(line, f).not.toContain('#additive-field')
        }
    })
})

/**
 * FDAW-66, architecture 4 row 6: a product administrator (organization READ_ONLY plus a COMPONENT
 * ADMIN grant on the product) can save the Medical device tab. `isWritable` is the pure predicate
 * `componentPageWritable` (utils/componentWrite.spec.ts runs its cells); this pins the wiring.
 */
describe('the component page counts a scoped ADMIN grant as write (FDAW-66)', () => {
    const code = source
        .replace(/<!--[\s\S]*?-->/g, '')
        .replace(/\/\*[\s\S]*?\*\//g, '')
        .replace(/(^|[^:])\/\/.*$/gm, '$1')
    const paneAt = code.indexOf('<n-tab-pane name="device"')
    const pane = code.slice(paneAt, code.indexOf('<n-tab-pane', paneAt + 1))
    /** Every opening tag of the slice, attributes included (quoted values may hold `>`-free text). */
    const tags = (s: string): string[] => s.match(/<[\w-]+(?:\s+[^\s"'>=/]+(?:="[^"]*")?)*\s*\/?>/g) || []

    it('computes isWritable from componentPageWritable and imports it', () => {
        expect(code).toMatch(/const\s+isWritable\s*:\s*ComputedRef<boolean>\s*=\s*computed\(\s*\(\)\s*:\s*boolean\s*=>\s*componentPageWritable\(\s*myUser\?\.permissions\?\.permissions\s*,\s*orguuid\.value\s*,\s*myPerspective\.value\s*,\s*componentUuid\s*\)\s*\)/)
        expect(code).toMatch(/import\s*\{\s*componentPageWritable\s*\}\s*from\s*'\.\.\/utils\/componentWrite'/)
    })

    it('keeps no READ_WRITE-only fallback and leaves isAdmin the organization ADMIN grant', () => {
        expect(code).not.toContain("type === 'READ_WRITE'")
        expect(code).toContain('const isAdmin : boolean = commonFunctions.isAdmin(orguuid.value, myUser)')
    })

    it('gates every Medical device control on isWritable', () => {
        expect(paneAt).toBeGreaterThan(-1)
        const controls = tags(pane).filter(t => /^<n-(radio-group|checkbox|select|switch|input|date-picker)\b/.test(t)
            && /\s:disabled="/.test(t))
        expect(controls.length).toBeGreaterThanOrEqual(9)
        for (const t of controls) {
            expect(t.match(/\s:disabled="([^"]*)"/)![1], t).toContain('!isWritable')
        }
        expect(code).toMatch(/v-if="\(hasDeviceChanges \|\| hasFdaChanges \|\| canCompleteSetup\) && isWritable"/)
    })

    it('offers no control for an organization step: those stay server-side', () => {
        const bound = tags(pane).filter(t => /\s(v-model(:[\w-]+)?|@update:[\w-]+|@click)="/.test(t))
        expect(bound.length).toBeGreaterThan(0)
        for (const t of bound) {
            for (const org of ['supportExport', 'fdaProfile', 'orgPackageProfile', 'ORG_']) {
                expect(t, org).not.toContain(org)
            }
        }
        // FDAW-78 row 17: the line element lives in MedicalDeviceSetupStatus.vue now; it still holds no control
        const lineAt = statusSource.indexOf("'device-setup-line-'")
        expect(lineAt).toBeGreaterThan(-1)
        const line = statusSource.slice(lineAt, statusSource.indexOf('</div>', lineAt))
        expect(line).not.toMatch(/<(n-[\w-]+|input|select|textarea|button)\b/)
    })
})
