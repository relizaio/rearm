// @vitest-environment happy-dom
//
// The upload dialog's import settings: for a VEX or SAFETY_CONTEXT upload it says that without
// Finding Analysis write at the chosen scope every statement is staged, asks the server for the
// import summary's demotions and override outcome, and lists them in the outcome dialog.
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, h, Suspense } from 'vue'
import { print } from 'graphql'

const query = vi.fn()
const mutate = vi.fn()
const fire = vi.fn()
vi.mock('@/utils/graphql', () => ({ default: { query, mutate } }))
vi.mock('sweetalert2', () => ({ default: { fire } }))
vi.mock('vuex', () => ({
    useStore: () => ({
        dispatch: vi.fn(),
        getters: { componentsOfOrg: () => [], allOrganizations: [], branchesOfComponent: () => [] }
    })
}))
const { default: CreateArtifact } = await import('./CreateArtifact.vue')
const { ADD_ARTIFACT_WITH_IMPORT_SUMMARY, UPDATE_ARTIFACT_WITH_IMPORT_SUMMARY } = await import('@/graphql/artifactImport')

const tooltip = { template: '<span><slot name="trigger"/><slot/></span>' }
// The file chooser: hands the form one chosen file as soon as it renders.
const upload = {
    emits: ['update:value'],
    mounted (this: any) { this.$emit('update:value', [{ file: new File(['{}'], 'doc.json') }]) },
    template: '<div/>'
}

async function mounted (type: string) {
    const updateArtifact = { uuid: 'art-1', type, displayIdentifier: 'doc-1', tags: [], inventoryTypes: [], bomFormat: 'CYCLONEDX' }
    const Host = defineComponent({
        setup: () => () => h(Suspense, null, {
            default: () => h(CreateArtifact, {
                inputOrgUuid: 'org-1', inputRelease: 'rel-1', inputBelongsTo: 'RELEASE', isUpdateExistingBom: true, updateArtifact
            })
        })
    })
    const w = mount(Host, { global: { stubs: { NTooltip: tooltip, Tooltip: tooltip, NUpload: upload, Upload: upload } } })
    await flushPromises()
    return w
}

function summaryResult (type: string, summary: any) {
    return { data: { addArtifactManual: { uuid: 'rel-1', artifactDetails: [{ uuid: 'art-2', displayIdentifier: 'doc-1', type, importSummary: summary }] } } }
}

async function submit (w: any) {
    await w.findAll('button').find((b: any) => b.text() === 'Add Artifact')!.trigger('click')
    await flushPromises()
}

/** The text of the last outcome dialog: its html element when it has one, else its text. */
function lastDialogText (): string {
    const opts = fire.mock.calls.at(-1)![0]
    return opts.html ? opts.html.textContent : opts.text
}

const SUMMARY = {
    statementsTotal: 3, proposalsCreated: 2, proposalsAutoAccepted: 1, proposalsAutoRejected: 0, attestationsDeferred: 0,
    statementsUnchanged: 0, statementsUnmatched: 0, statementsErrored: 0, errorMessages: [],
    demotions: [{ reason: 'NO_FINDING_ANALYSIS_WRITE', count: 2 }], overrideOutcome: 'IGNORED'
}

describe('CreateArtifact import settings', () => {
    beforeEach(() => {
        query.mockReset(); mutate.mockReset(); fire.mockReset()
        query.mockResolvedValue({ data: { artifactTypes: ['BOM', 'VEX', 'SAFETY_CONTEXT'] } })
    })

    it.each(['VEX', 'SAFETY_CONTEXT'])('says a %s upload without the permission is staged', async (type) => {
        const w = await mounted(type)
        const note = w.find('[data-testid="import-permission-note"]')
        expect(note.exists()).toBe(true)
        expect(note.text()).toBe('Without Finding Analysis write at the chosen scope, every statement is staged for review.')
    })

    it('shows no import note for an upload that runs no import', async () => {
        const w = await mounted('BOM')
        expect(w.find('[data-testid="import-permission-note"]').exists()).toBe(false)
    })

    it('names the permission each VEX scope needs', async () => {
        const w = await mounted('VEX')
        expect(w.text()).toContain('Component on this component; Release on this release (or its component); Branch and Organization on the organization')
        expect(w.text()).toContain('Statements take effect only if you hold Finding Analysis write at the chosen scope.')
        expect(w.text()).toContain('Choosing a more trusted class needs Finding Analysis write at the chosen scope')
    })

    it('drops the old override sentence from the safety issuer tooltip', async () => {
        const w = await mounted('SAFETY_CONTEXT')
        expect(w.text()).not.toContain('An override needs Finding Analysis write permission')
        expect(w.text()).toContain('Choosing a less trusted class than the upload\'s own always applies.')
    })

    it.each(['VEX', 'SAFETY_CONTEXT'])('asks for the %s import summary with demotions and the override outcome', async (type) => {
        const w = await mounted(type)
        mutate.mockResolvedValue(summaryResult(type, SUMMARY))
        await submit(w)
        expect(mutate).toHaveBeenCalledTimes(1)
        const doc = print(mutate.mock.calls[0][0].mutation)
        expect(doc).toMatch(/importSummary\s*\{[^}]*demotions\s*\{\s*reason\s+count\s*\}\s*overrideOutcome/)
        expect(doc).not.toContain('vexImportSummary')
        expect(mutate.mock.calls[0][0].variables.artifactUuid).toBe('art-1')
    })

    it.each(['VEX', 'SAFETY_CONTEXT'])('lists why %s statements were staged and that the override was not applied', async (type) => {
        const w = await mounted(type)
        mutate.mockResolvedValue(summaryResult(type, SUMMARY))
        await submit(w)
        const text = lastDialogText()
        expect(text).toContain('2 staged: you need Finding Analysis write at this scope')
        expect(text).toContain('Issuer override not applied')
        const reasons = fire.mock.calls.at(-1)![0].html.querySelectorAll('[data-testid="import-outcome-reasons"] li')
        expect(reasons.length).toBe(2)
    })

    it('says nothing about the override when it was applied, and lists no reasons when nothing was demoted', async () => {
        const w = await mounted('VEX')
        mutate.mockResolvedValue(summaryResult('VEX', { ...SUMMARY, proposalsCreated: 0, proposalsAutoAccepted: 3, demotions: [], overrideOutcome: 'LOWERED' }))
        await submit(w)
        const text = lastDialogText()
        expect(text).toContain('3 auto-accepted')
        expect(text).not.toContain('Issuer override not applied')
        expect(fire.mock.calls.at(-1)![0].html.querySelector('[data-testid="import-outcome-reasons"]')).toBeNull()
    })

    it('both import documents select demotions and the override outcome', () => {
        for (const doc of [ADD_ARTIFACT_WITH_IMPORT_SUMMARY, UPDATE_ARTIFACT_WITH_IMPORT_SUMMARY]) {
            const text = print(doc)
            expect(text).toContain('demotions')
            expect(text).toContain('overrideOutcome')
        }
    })
})
