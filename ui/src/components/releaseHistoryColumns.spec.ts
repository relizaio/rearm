// @vitest-environment happy-dom
//
// FDAW-78 row 13 (REQ-66): the release History's Action and Scope columns, mounted from the same
// definitions ReleaseView.vue uses (utils/releaseHistoryRender.ts), with a details column written here
// that mirrors ReleaseView.vue's details branches for SUBMISSION, SYNTHETIC_ARTIFACT and FDA_NARRATIVE and
// its `row.objectId` fallback. The sweep is scoped (F-3): the Action and Scope cells of every row and the
// details of the FDA rows; a generic row's details keep their uuid (3.4), which the sixth row shows.
import { describe, expect, it, vi } from 'vitest'
import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'
import { h } from 'vue'
import { mount } from '@vue/test-utils'
import { NDataTable } from 'naive-ui'
import { historyActionColumn, historyScopeColumn } from '@/utils/releaseHistoryRender'

// submissions.ts and generatedArtifacts.ts reach the fetch client, whose keycloak setup needs a browser
vi.mock('@/utils/fetchClient', () => ({ fetchWithAuth: vi.fn(), fetchArrayBufferWithAuth: vi.fn() }))

const { submissionHistoryText } = await import('@/utils/submissions')
const { syntheticHistoryText } = await import('@/utils/generatedArtifacts')
import { formatNarrativeChange } from '@/utils/narrativeHistory'
import { expectSwept, UUID_RE } from '@/utils/__fixtures__/fdaSweep'

const UUID = '3bef7cde-2edb-47cb-aa4e-94487c2cdced'
const ART = 'a1a1a1a1-0000-4000-8000-000000000001'
const DETAILS = [{ uuid: ART, type: 'REGULATORY_DOCUMENT', displayIdentifier: 'x-narrative.txt' }]

/** The details column as ReleaseView.vue renders these scopes (:8301-8318 and :8356 at 7d425a5d). */
const detailsColumn = {
    key: 'objectId',
    title: 'Event or Object Details',
    render: (row: any) => {
        if (row.rus === 'FDA_NARRATIVE') return formatNarrativeChange(row.oldValue, row.newValue)
        if (row.rus === 'SYNTHETIC_ARTIFACT') return syntheticHistoryText(row.objectId, DETAILS)
        if (row.rus === 'SUBMISSION') return submissionHistoryText(row)
        return row.objectId
    },
}

const ROWS = [
    { key: 1, rus: 'SUBMISSION', rua: 'ADDED', oldValue: null, newValue: 'FDA_PREMARKET v1', objectId: 's-1' },
    { key: 2, rus: 'SUBMISSION', rua: 'REMOVED', oldValue: 'FINAL v1', newValue: 'CANCELLED', objectId: 's-1' },
    { key: 3, rus: 'SUBMISSION', rua: 'ADDED', oldValue: null, newValue: 'FDA_PREMARKET draft', objectId: 's-1' },
    { key: 4, rus: 'SYNTHETIC_ARTIFACT', rua: 'ADDED', oldValue: null, newValue: null, objectId: ART },
    { key: 5, rus: 'FDA_NARRATIVE', rua: 'CHANGED', oldValue: null, newValue: 'x', objectId: null },
    { key: 6, rus: 'PARENT_RELEASE', rua: 'ADDED', oldValue: null, newValue: null, objectId: UUID },
]

function cells () {
    const w = mount(NDataTable, { props: { columns: [historyActionColumn, historyScopeColumn, detailsColumn], data: ROWS,
        rowKey: (r: any) => r.key } })
    const rows = w.findAll('tbody tr')
    expect(rows).toHaveLength(6)
    return rows.map((r: any) => r.findAll('td').map((c: any) => c.text()))
}

describe('the release History columns in words (FDAW-78 row 13)', () => {
    it('the Action, Scope and details cells of the six rows', () => {
        const c = cells()
        expect(c.map(r => r[0])).toEqual(['Created', 'Cancelled', 'Draft started', 'Added', 'Changed', 'Added'])
        expect(c.map(r => r[1])).toEqual(['Regulatory submission', 'Regulatory submission', 'Regulatory submission',
            'Generated artifact', 'FDA assessment narrative', 'Parent release'])
        expect(c.slice(0, 5).map(r => r[2])).toEqual([
            'FDA premarket submission: version 1 created',
            'Regulatory submission: version 1 cancelled',
            'FDA premarket submission: draft started',
            'Generated artifact: Regulatory document x-narrative.txt',
            'narrative set (1 character)',
        ])
    })

    it('the scoped sweep: Action and Scope of every row and the FDA details pass; the generic details keep their uuid', () => {
        const c = cells()
        const swept = [...c.map(r => r[0]), ...c.map(r => r[1]), ...c.slice(0, 5).map(r => r[2])]
        expect(swept).toHaveLength(17)
        for (const s of swept) expect(s).not.toBe('')
        expectSwept(swept.join('\n'), 'history')
        // the sixth row's details are left (3.4): the scope, not a loosened expression, is what passes
        expect(c[5][2]).toMatch(UUID_RE)
    })
})

describe('the History columns are wired into ReleaseView.vue (FDAW-78 row 13)', () => {
    const read = (f: string) => readFileSync(fileURLToPath(new URL(f, import.meta.url)), 'utf8')
    const strip = (s: string) => s
        .replace(/<!--[\s\S]*?-->/g, '')
        .replace(/\/\*[\s\S]*?\*\//g, '')
        .replace(/(^|[^:])\/\/.*$/gm, '$1')
    const view = strip(read('./ReleaseView.vue'))
    const render = strip(read('../utils/releaseHistoryRender.ts'))

    it('imports the two columns and uses them in releaseHistoryFields, with no rua or rus literal left', () => {
        expect(view).toMatch(/import \{ historyActionColumn, historyScopeColumn \} from '@\/utils\/releaseHistoryRender'/)
        const at = view.indexOf('const releaseHistoryFields = computed(() => [')
        expect(at).toBeGreaterThan(-1)
        const fields = view.slice(at, view.indexOf('\n])', at))
        expect(fields).toContain('historyActionColumn')
        expect(fields).toContain('historyScopeColumn')
        expect(view).not.toContain("key: 'rua'")
        expect(view).not.toContain("key: 'rus'")
        // control: the module carries both keys
        expect(render).toContain("key: 'rua'")
        expect(render).toContain("key: 'rus'")
    })
})
