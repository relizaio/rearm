import { describe, it, expect } from 'vitest'
import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'
import { escapeRegExp } from '@/utils/specText'

/**
 * Import and call-site assertions for the attestation form, comment-aware.
 *
 * Same rationale as the coverage wiring spec: this repo has no vue-tsc, so an undefined
 * identifier in <script setup> is a runtime ReferenceError that `vite build` and the whole
 * vitest suite will happily pass -- which is exactly how the coverage gauge shipped a blank
 * tab. Behaviour lives in useAttestationForm.spec.ts; this covers the seam.
 *
 * Honest about the limit: this catches "removed or commented out", not "made conditional".
 */
const source = readFileSync(
    fileURLToPath(new URL('./ReleaseView.vue', import.meta.url)), 'utf8')
const code = source
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .split('\n')
    .map(l => l.replace(/(^|[^:])\/\/.*$/, '$1'))
    .join('\n')

/**
 * The whole body of a top-level function, rather than a fixed number of characters.
 *
 * The first version sliced 1400 chars from the declaration, and two assertions started
 * failing the moment saveAttestation grew -- reporting missing wiring that was present a few
 * lines further down. A guard that fails as the code it guards gets longer is a guard that
 * will be deleted.
 */
function functionBody (name: string): string {
    const start = code.indexOf(`async function ${name} (`)
    if (start < 0) return ''
    const rest = code.slice(start + 1)
    const next = rest.search(/\n(async )?function /)
    return next < 0 ? rest : rest.slice(0, next)
}

describe('the attestation form is wired into ReleaseView', () => {
    it.each([
        ['useAttestationForm', '@/utils/useAttestationForm'],
        ['loadSbomComponentSupportDetail', '@/utils/sbomComponentSupportDetail'],
        ['setSbomComponentSupportVars', '@/utils/setSbomComponentSupport']
    ])('imports %s from %s', (symbol, module) => {
        expect(source, `${symbol} is used but not imported -- the build will not catch this`)
            .toMatch(new RegExp(
                `import\\s+(type\\s+)?\\{[^}]*\\b${symbol}\\b[^}]*\\}\\s+from\\s+'${escapeRegExp(module)}'`))
    })

    /**
     * "Used" means invoked OR bound as a template handler. Vue binds handlers by name --
     * `@click="saveAttestation"` with no parentheses -- so a call-site regex alone reports
     * a correctly-wired handler as dead. The first version of this test did exactly that
     * and failed on two working handlers.
     */
    it.each(['openAttestForm', 'saveAttestation', 'applyNothingPublished'])(
        'references %s in live code, not only declaring it', (fn) => {
            const invoked = code.match(new RegExp(`(?<!function\\s{1,10})\\b${fn}\\s*\\(`, 'g')) || []
            const bound = code.match(new RegExp(`="\\s*${fn}\\s*"`, 'g')) || []
            expect(invoked.length + bound.length,
                `${fn} is neither called nor bound to a handler -- it is dead`)
                .toBeGreaterThan(0)
        })

    /**
     * The write must refresh the gauge. The gauge only re-reads with the list, and an
     * attestation is precisely what moves it -- so a save that skipped this would succeed
     * while the number above the table stayed stale, which reads to an operator as a failure.
     */
    it('refreshes the list AND gauge after a successful save', () => {
        const save = functionBody('saveAttestation')
        expect(save, 'saveAttestation must force a reload so the gauge re-reads')
            .toMatch(/loadSbomComponents\s*\(\s*true\s*\)/)
    })

    /**
     * Notes must be rendered per milestone, not as one component-level box. The server
     * stores them against the staged milestone, so a single box discarded a notes-only edit
     * and mislabelled notes saved alongside one date.
     */
    it('renders notes per milestone rather than one component-level box', () => {
        expect(code).toContain('milestoneNotes[m.type]')
        expect(code).not.toContain('form.supportNotes')
    })

    // One supportNotes argument per call means two milestones must be serialised.
    it('serialises the writes rather than issuing one', () => {
        expect(functionBody('saveAttestation'))
            .toMatch(/for\s*\(\s*const\s+vars\s+of\s+sets/)
    })

    // A double-click on OK is the classic way a modal writes twice; under an audit trail
    // that shows up as two revisions for one intended edit.
    it('guards the save against a double submit', () => {
        expect(functionBody('saveAttestation')).toMatch(/attestSaving\.value/)
    })

    /**
     * A serialised save can land call 1 and fail call 2. Reporting that as a plain failure
     * makes the operator resubmit both, re-stamping the milestone that already landed.
     */
    it('folds completed writes into the baseline on a partial failure', () => {
        const save = functionBody('saveAttestation')
        expect(save, 'a partial save must not leave landed writes pending')
            .toMatch(/markSaved\s*\(\s*done\s*\)/)
    })

    // Root components are never attested: the server skips them and the gauge excludes them.
    it('does not offer the action on root components', () => {
        expect(code).toMatch(/isRoot[\s\S]{0,200}openAttestForm|openAttestForm[\s\S]{0,200}isRoot/)
    })
})

/** FDAW-14 row 16: organization write gates the support half, not the hash half. By source. */
describe('organization write gates the bulk support controls', () => {
    it('imports hasOrgWrite and orgWriteSentence and computes canWriteOrg with it', () => {
        expect(source).toMatch(/import\s+\{[^}]*\bhasOrgWrite\b[^}]*\}\s+from\s+'@\/utils\/orgWrite'/)
        expect(code).toMatch(/const canWriteOrg[^=]*= computed\(\(\)[^=]*=>\s*hasOrgWrite\(/)
        expect(code).toMatch(/orgWriteSentence\('Attest all shown'\)/)
    })

    it('renders Attest all shown disabled with the sentence for a caller without it', () => {
        const at = source.indexOf('Attest all shown\n')
        const button = source.slice(source.lastIndexOf('<n-button', at), at)
        expect(button).toContain(':disabled="!canWriteOrg"')
        expect(button).toContain('ATTEST_ALL_ORG_WRITE_SENTENCE')
        const line = source.slice(source.indexOf('data-testid="attest-all-org-write"') - 200,
            source.indexOf('data-testid="attest-all-org-write"') + 200)
        expect(line).toContain('!canWriteOrg')
        expect(line).toContain('{{ ATTEST_ALL_ORG_WRITE_SENTENCE }}')
    })

    it('offers Bulk assessment under isWritable, so a reader sees neither control', () => {
        const at = source.indexOf('data-testid="bulk-assessment"')
        const button = source.slice(source.lastIndexOf('<n-button', at), at)
        expect(button).toMatch(/v-if="[^"]*isWritable[^"]*"/)
        expect(button).not.toContain('canWriteOrg')
    })

    it('disables the support half with the sentence at its top, and leaves Missing hashes enabled', () => {
        const support = source.slice(source.indexOf("bulkStage === 'compose' && bulkTab === 'support'"))
        expect(support.slice(0, 400)).toContain('v-if="!canWriteOrg"')
        expect(support.slice(0, 400)).toContain('BULK_ASSESSMENT_ORG_WRITE_SENTENCE')
        expect(support).toContain(':disabled="bulk.collecting.value || !canWriteOrg"')
        const hashes = source.slice(source.indexOf("bulkStage === 'compose' && bulkTab === 'hashes'"))
        const block = hashes.slice(0, hashes.indexOf('</div>'))
        expect(block).toContain('<missing-hashes-panel')
        expect(block).not.toContain('canWriteOrg')
    })

    /**
     * The form inside the support half is disabled for a caller without organization write,
     * but "Review selection" is a separate footer button with its own :disabled -- disabling
     * the inputs does not stop a click on a button outside the form. Without !canWriteOrg
     * here too, that caller could still advance to the confirm stage with the form's defaults.
     */
    it('also disables Review selection for a caller without organization write', () => {
        const at = source.indexOf('Review selection</n-button>')
        const button = source.slice(source.lastIndexOf('<n-button', at), at)
        expect(button).toContain('!canWriteOrg')
    })
})

/** The body of a top-level function, sync or async. */
function anyFunctionBody (name: string): string {
    const at = code.search(new RegExp(`\\n(async )?function ${name} \\(`))
    if (at < 0) return ''
    const rest = code.slice(at + 1)
    const next = rest.slice(1).search(/\n(async )?function /)
    return next < 0 ? rest : rest.slice(0, next + 1)
}

/** The source of the element carrying data-testid `id`, from its opening tag's start. */
function elementAt (id: string, length = 600): string {
    const at = source.indexOf(`data-testid="${id}"`)
    if (at < 0) return ''
    return source.slice(source.lastIndexOf('<', at), at + length)
}

/** FDAW-15 row 14 (REQ-13) and FDAW-51 3.3.1, 3.3.2: the overlay editor, the panel and the vendored target. */
describe('the overlay UI is wired into ReleaseView', () => {
    it.each([
        ['loadComponentOverlay', '@/utils/overlayInput'],
        ['overlayEditorAccess', '@/utils/overlayInput'],
        ['saveComponentOverlay', '@/utils/overlayInput'],
        ['loadUnidentifiedComponents', '@/utils/vendoredComponents'],
        ['loadVendoredSupportDetail', '@/utils/vendoredComponents'],
        ['vendoredRowActions', '@/utils/vendoredComponents'],
        ['vendoredStateTag', '@/utils/vendoredComponents'],
        ['setVendoredComponentSupportVars', '@/utils/setSbomComponentSupport'],
        ['submitSupplierBySelector', '@/utils/bulkSelectorForm']
    ])('imports %s from %s', (symbol, module) => {
        expect(source).toMatch(new RegExp(
            `import\\s+(type\\s+)?\\{[^}]*\\b${symbol}\\b[^}]*\\}\\s+from\\s+'${escapeRegExp(module)}'`))
    })

    it('pushes overlay-edit under the same isWritable and isRoot guard as Attest, so a root and a reader have none', () => {
        const at = code.indexOf("'data-testid': 'overlay-edit'")
        expect(at).toBeGreaterThan(0)
        expect(code.slice(at - 200, at)).toMatch(/if \(isWritable\.value && !row\.component\?\.isRoot\) \{/)
        expect(code.slice(at, at + 200)).toContain('openOverlayEditor(row)')
        // After Attest, in the same els.
        expect(code.indexOf('openAttestForm(row)')).toBeLessThan(at)
    })

    it('opens the editor read-only with the sentence for a caller without organization write', () => {
        expect(code).toMatch(/const overlayAccess = computed\(\(\) => overlayEditorAccess\(canWriteOrg\.value\)\)/)
        const alert = elementAt('overlay-org-write', 120)
        expect(alert).toMatch(/^<n-alert v-if="overlayAccess\.readOnly"/)
        expect(alert).toContain('{{ overlayAccess.sentence }}')
        const editor = source.slice(source.indexOf('data-testid="overlay-editor"'), source.indexOf('data-testid="overlay-save"'))
        expect(editor).toContain('<n-form label-placement="top" :disabled="overlayAccess.readOnly">')
        expect(editor).toContain('<n-button v-if="!overlayAccess.readOnly"')
        expect(anyFunctionBody('saveOverlay')).toMatch(/overlayAccess\.value\.readOnly/)
    })

    it('renders the panel under the SBOM list, fed on the SBOM tab, with the server\'s values as text', () => {
        expect(source.indexOf('data-testid="unidentified-components"'))
            .toBeGreaterThan(source.indexOf(':columns="sbomComponentsTableFields"'))
        expect(anyFunctionBody('handleBomSubTabSwitch')).toMatch(/loadVendoredPanel\(\)/)
        expect(anyFunctionBody('loadVendoredPanel')).toMatch(/loadUnidentifiedComponents\(/)
        expect(source).toMatch(/:title="vendoredPanelTitle\(vendoredRows\)"/)
        expect(source).toContain('{{ vendoredStateTag(v) }}')
        expect(source.match(/v-html/g)?.length ?? 0).toBe(1)
    })

    it.each(['describe', 'attest', 'remove'])('the panel\'s %s goes through vendoredRowActions with isWritable and canWriteOrg', (a) => {
        const button = elementAt(`vendored-${a}`, 300)
        expect(button).toContain(`vendoredRowActions(v, isWritable, canWriteOrg).${a}.shown`)
        expect(button).toContain(`vendoredRowActions(v, isWritable, canWriteOrg).${a}.disabled`)
    })

    it('refetches the panel after every mutation', () => {
        expect(anyFunctionBody('saveVendoredDescription')).toMatch(/loadVendoredPanel\(\)/)
        expect(anyFunctionBody('confirmVendoredRemove')).toMatch(/loadVendoredPanel\(\)/)
        expect(functionBody('saveAttestation')).toMatch(/kind === 'vendored'\) await loadVendoredPanel\(\)/)
    })

    it('openVendoredAttestForm seeds through loadVendoredSupportDetail under the shared attestGen', () => {
        const open = anyFunctionBody('openVendoredAttestForm')
        expect(open).toContain('loadVendoredSupportDetail(')
        expect(open).not.toContain('loadSbomComponentSupportDetail')
        expect(open).not.toContain('getReleaseSbomComponentGraph')
        expect(open).toMatch(/const gen = \+\+attestGen/)
        const afterAwait = open.slice(open.indexOf('await loadVendoredSupportDetail'))
        expect(afterAwait).toMatch(/^[\s\S]{0,200}if \(gen !== attestGen\) return/)
        expect(anyFunctionBody('cancelAttest')).toMatch(/attestGen \+= 1/)
    })

    it('saves the vendored target through setVendoredComponentSupportVars, and titles the modal by target', () => {
        const save = functionBody('saveAttestation')
        expect(save).toMatch(/if \(target\.kind === 'vendored'\) await setVendoredComponentSupportVars\(/)
        expect(save).toMatch(/else await setSbomComponentSupportVars\(/)
        expect(source).toContain("'Record support attestation (vendored component)'")
    })
})

/** FDAW-15 row 16 (REQ-15) and FDAW-51 3.3.3: the Supplier half of the bulk dialog. */
describe('the Supplier half is wired into ReleaseView', () => {
    it('is the third button of the bulk-half group', () => {
        const group = source.slice(source.indexOf('data-testid="bulk-half"'), source.indexOf('</n-radio-group>',
            source.indexOf('data-testid="bulk-half"')))
        const values = [...group.matchAll(/<n-radio-button value="([a-z]+)"/g)].map(m => m[1])
        expect(values).toEqual(['support', 'hashes', 'supplier'])
        expect(group).toContain('data-testid="bulk-supplier-tab"')
    })

    it('offers the two selector forms only, disabled with its sentence without organization write', () => {
        const half = source.slice(source.indexOf("bulkStage === 'compose' && bulkTab === 'supplier'"))
        expect(half.slice(0, 400)).toContain('v-if="!canWriteOrg"')
        expect(half.slice(0, 400)).toContain('BULK_SUPPLIER_ORG_WRITE_SENTENCE')
        expect(half).toContain('<n-form label-placement="top" :disabled="!canWriteOrg">')
        expect(half).toContain('v-for="o in SUPPLIER_SELECTOR_FORM_OPTIONS"')
        expect(code).toMatch(/orgWriteSentence\('A bulk supplier assignment'\)/)
    })

    it('shows the count, and disables confirm over the cap, on a blank supplier or without organization write', () => {
        const button = source.slice(source.lastIndexOf('<n-button', source.indexOf('data-testid="bulk-supplier-confirm"')),
            source.indexOf('data-testid="bulk-supplier-confirm"'))
        expect(button).toContain('bulkSupplierErrors.length > 0')
        expect(button).toContain('bulkSelectorCount.overCap')
        expect(button).toContain('!canWriteOrg')
        const stage = source.slice(source.indexOf("bulkStage === 'confirm' && bulkTab === 'supplier'"))
        expect(stage.slice(0, 500)).toContain('data-testid="bulk-selector-count"')
        expect(anyFunctionBody('collectSupplierCount')).toMatch(/fetchSelectorCount\(/)
        expect(anyFunctionBody('runBulkSupplier')).toMatch(/submitSupplierBySelector\(/)
        expect(anyFunctionBody('runBulkSupplier')).not.toMatch(/bulk\.submit\(|bulk\.collect\(/)
    })

    it('the result stage renders skippedHasSupplier and says assigned a supplier for the supplier half', () => {
        const counts = elementAt('bulk-result-counts', 300)
        expect(counts).toContain("bulkResultHalf === 'supplier' ? 'assigned a supplier' : 'attested'")
        expect(source).toContain('<span v-if="bulkResult.skippedHasSupplier"> {{ bulkResult.skippedHasSupplier }}'
            + ' skipped: already hold a supplier.</span>')
        expect(anyFunctionBody('runBulkSupplier')).toMatch(/bulkResultHalf\.value = 'supplier'/)
        expect(anyFunctionBody('runBulkAttest')).toMatch(/bulkResultHalf\.value = 'support'/)
    })
})
